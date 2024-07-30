/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.IacResourceMappingProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ContainerType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.InstanceTypeLayout
import com.morpheusdata.model.OsType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResourceMappingResponse
import com.morpheusdata.response.InstanceResourceMappingResponse

class NutanixPrismIacResourceMappingProvider implements IacResourceMappingProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext

	NutanixPrismIacResourceMappingProvider(NutanixPrismPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return 'nutanix-prism-iac-resource-mapping-provider'
	}

	@Override
	String getName() {
		return "Nutanix Prism IaC Resource Mapping Provider"
	}

	@Override
	List<String> getIacProvisionTypeCodes() {
		['terraform']
	}

	@Override
	ServiceResponse<InstanceResourceMappingResponse> resolveInstance(Instance instance, AccountResource resource, Map resourceResult, String iacProvider, String iacProviderType, String iacType) {
		//only supports terraform at the moment
		if (iacProvider == 'terraform') {
			InstanceResourceMappingResponse response = new InstanceResourceMappingResponse()
			def ip_address = resourceResult?.values?.nic_list?.getAt(0)?.ip_endpoint_list?.getAt(0)?.ip
			if(!ip_address) {
				ip_address = resourceResult?.values?.nic_list_status?.getAt(0)?.ip_endpoint_list?.getAt(0)?.ip
			}
			def externalId = resourceResult?.values?.metadata?.uuid
			response.privateIp = ip_address
			response.publicIp = ip_address
			response.noAgent = true
			response.installAgent = false
			def serverName = resourceResult?.values?.name
			def clusterId = resourceResult?.values?.cluster_uuid
			def cluster = morpheusContext.async.cloud.pool.find(new DataQuery().withFilter("externalId", clusterId)).blockingGet()
			def serverList = instance.containers?.collect{ it.server }
			def server = externalId ? serverList?.find{ it.externalId == externalId } : null
			if(server == null && ip_address != null)
				server = serverList?.find{ it.externalIp == ip_address }
			if(server == null && serverName != null)
				server = serverList?.find{ it.name == serverName }
			if(server == null)
				server = serverList?.find{ it.internalIp == null && it.externalIp == null && it.externalId == null }
			if(serverName && instance.displayName != serverName) {
				instance.name = serverName
			}
			if (server) {
				response.serverId = server.id
				server.externalId = externalId
				server.maxMemory = resourceResult?.values?.memory_size_mib * ComputeUtility.ONE_MEGABYTE
				server.maxCores = (resourceResult?.values?.num_vcpus_per_socket?.toLong() ?: 0) * (resourceResult?.values?.num_sockets?.toLong() ?: 0)
				server.coresPerSocket = resourceResult?.values?.num_vcpus_per_socket?.toLong()
				server.resourcePool = cluster
				if (serverName && server.name != serverName)
					server.name = serverName
				server.computeServerType = new ComputeServerType(code: 'nutanix-prism-unmanaged')
				//set extra config
				instance.instanceTypeCode = 'nutanix-prism-provision-provider'
				instance.layout = new InstanceTypeLayout(code: 'nutanix-prism-1.0-single')
				def workloads = morpheusContext.async.workload.listById(instance.containers.collect {it.id}).toList().blockingGet()
				workloads?.each { row ->
					row.workloadType = new WorkloadType(code: 'nutanix-prism-provision-provider-1.0')
				}
				morpheusContext.async.workload.save(workloads).blockingGet()
				//find the zone in case different than selected?
				//find a matching image and configure agent etc...
				def disk = resourceResult.values?.disk_list?.find { it?.data_source_reference?.kind == "image" }
				def sourceImageId = disk?.data_source_reference?.uuid

				if (sourceImageId) {
					def virtualImageLocation = morpheusContext.async.virtualImage.location.find(new DataQuery().withFilter("externalId", sourceImageId)).blockingGet()
					def virtualImageProj = virtualImageLocation?.virtualImage
					if (virtualImageProj) {
						def virtualImage = morpheusContext.async.virtualImage.get(virtualImageProj.id).blockingGet()
						server.sourceImage = new VirtualImage(id: virtualImage.id)
						server.serverOs = virtualImage.osType ?: new OsType(code: 'other.64')
						server.osType = virtualImage.platform
						server.platform = virtualImage.platform
						//cs type
						def platform = virtualImage.platform
						def csTypeCode = platform == 'windows' ? 'nutanix-prism-windows-vm' : 'nutanix-prism-vm'
						server.computeServerType = new ComputeServerType(code: csTypeCode)
					}
				}
				morpheusContext.async.computeServer.save(server).blockingGet()
				morpheusContext.async.instance.save([instance]).blockingGet()
			}
			return ServiceResponse.success(response)
		} else {
			return ServiceResponse.error("IaC Provider ${iacProvider} not supported")
		}
	}

	@Override
	ServiceResponse<WorkloadResourceMappingResponse> resolveWorkload(Workload workload, AccountResource resource, Map resourceResult, String iacProvider, String iacProviderType, String iacType) {
		//only supports terraform at the moment
		if (iacProvider == 'terraform') {
			WorkloadResourceMappingResponse response = new WorkloadResourceMappingResponse()
			def ip_address = resourceResult?.values?.nic_list?.getAt(0)?.ip_endpoint_list?.getAt(0)?.ip
			if(!ip_address) {
				ip_address = resourceResult?.values?.nic_list_status?.getAt(0)?.ip_endpoint_list?.getAt(0)?.ip
			}
			def externalId = resourceResult?.values?.metadata?.uuid
			response.privateIp = ip_address
			response.publicIp = ip_address
			response.noAgent = true
			response.installAgent = false
			def serverName = resourceResult?.values?.name
			def clusterId = resourceResult?.values?.cluster_uuid
			def cluster = morpheusContext.async.cloud.pool.find(new DataQuery().withFilter("externalId", clusterId)).blockingGet()
			def server = workload.server
			if (server) {
				server.externalId = externalId
				server.maxMemory = resourceResult?.values?.memory_size_mib * ComputeUtility.ONE_MEGABYTE
				server.maxCores = (resourceResult?.values?.num_vcpus_per_socket?.toLong() ?: 0) * (resourceResult?.values?.num_sockets?.toLong() ?: 0)
				server.coresPerSocket = resourceResult?.values?.num_vcpus_per_socket?.toLong()
				server.resourcePool = cluster
				if (serverName && server.name != serverName)
					server.name = serverName
				server.computeServerType = new ComputeServerType(code: 'nutanix-prism-unmanaged')
				//set extra config
				workload.workloadType = new WorkloadType(code: 'nutanix-prism-provision-provider-1.0"')
				//find the zone in case different than selected?
				//find a matching image and configure agent etc...
				def disk = resourceResult.values?.disk_list?.find { it?.data_source_reference?.kind == "image" }
				def sourceImageId = disk?.data_source_reference?.uuid

				if (sourceImageId) {
					def virtualImageLocation = morpheusContext.async.virtualImage.location.find(new DataQuery().withFilter("externalId", sourceImageId)).blockingGet()
					def virtualImageProj = virtualImageLocation?.virtualImage
					if (virtualImageProj) {
						def virtualImage = morpheusContext.async.virtualImage.get(virtualImageProj.id).blockingGet()
						server.sourceImage = new VirtualImage(id: virtualImage.id)
						server.serverOs = virtualImage.osType ?: new OsType(code: 'other.64')
						server.osType = virtualImage.platform
						server.platform = virtualImage.platform
						//cs type
						def platform = virtualImage.platform
						def csTypeCode = platform == 'windows' ? 'nutanix-prism-windows-vm' : 'nutanix-prism-vm'
						server.computeServerType = new ComputeServerType(code: csTypeCode)
					}
				}
				morpheusContext.async.computeServer.save(server).blockingGet()
				morpheusContext.async.workload.save(workload).blockingGet()
			}
			return ServiceResponse.success(response)
		} else {
			return ServiceResponse.error("IaC Provider ${iacProvider} not supported")
		}

	}

}
