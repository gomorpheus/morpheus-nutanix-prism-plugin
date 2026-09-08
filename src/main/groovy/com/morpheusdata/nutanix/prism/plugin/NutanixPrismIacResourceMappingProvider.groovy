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
import com.morpheusdata.core.data.DataFilter
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
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismSyncUtils
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResourceMappingResponse
import com.morpheusdata.response.InstanceResourceMappingResponse
import groovy.util.logging.Slf4j

@Slf4j
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
			def values = resourceResult?.values
			// nutanix_virtual_machine_v2 (v4-API-backed) has a top-level `ext_id`, which the legacy
			// nutanix_virtual_machine (v1) schema never has - use its presence to distinguish the two
			// incompatible Terraform state schemas.
			def isV2Schema = values?.containsKey('ext_id')
			def ip_address = extractIpAddress(values, isV2Schema)
			def externalId = isV2Schema ? values?.ext_id : values?.metadata?.uuid
			response.privateIp = ip_address
			response.publicIp = ip_address
			response.noAgent = true
			response.installAgent = false
			def serverName = values?.name
			def clusterId = isV2Schema ? values?.cluster?.ext_id : values?.cluster_uuid
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
				def coresPerSocket = (isV2Schema ? values?.num_cores_per_socket : values?.num_vcpus_per_socket)?.toLong() ?: 0
				def memoryMib = isV2Schema ? ((values?.memory_size_bytes as Long ?: 0) / ComputeUtility.ONE_MEGABYTE) : (values?.memory_size_mib as Long ?: 0)
				server.maxMemory = memoryMib * ComputeUtility.ONE_MEGABYTE
				server.maxCores = coresPerSocket * (values?.num_sockets?.toLong() ?: 0)
				server.coresPerSocket = coresPerSocket
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
				def sourceImageId = extractSourceImageId(values, isV2Schema)

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
				def tags = getAllTags(server.cloud)
				def vmTags = isV2Schema ? resolveV2CategoryTags(values?.categories) : values?.categories?.collect {"${it.name}:${it.value}"}
				def existingTags = server.metadata
				def matchFunction = {existingTag, masterTag -> {
					masterTag == existingTag.externalId
				}}
				def tagSyncLists = NutanixPrismSyncUtils.buildSyncLists(existingTags, vmTags, matchFunction)
				//only need to add non-existing tags
				tagSyncLists.addList?.each {
					if (tags[it]) {
						server.metadata += tags[it]
					}
				}
				morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
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
			def values = resourceResult?.values
			def isV2Schema = values?.containsKey('ext_id')
			def ip_address = extractIpAddress(values, isV2Schema)
			def externalId = isV2Schema ? values?.ext_id : values?.metadata?.uuid
			response.privateIp = ip_address
			response.publicIp = ip_address
			response.noAgent = true
			response.installAgent = false
			def serverName = values?.name
			def clusterId = isV2Schema ? values?.cluster?.ext_id : values?.cluster_uuid
			def cluster = morpheusContext.async.cloud.pool.find(new DataQuery().withFilter("externalId", clusterId)).blockingGet()
			def server = workload.server
			if (server) {
				server.externalId = externalId
				def coresPerSocket = (isV2Schema ? values?.num_cores_per_socket : values?.num_vcpus_per_socket)?.toLong() ?: 0
				def memoryMib = isV2Schema ? ((values?.memory_size_bytes as Long ?: 0) / ComputeUtility.ONE_MEGABYTE) : (values?.memory_size_mib as Long ?: 0)
				server.maxMemory = memoryMib * ComputeUtility.ONE_MEGABYTE
				server.maxCores = coresPerSocket * (values?.num_sockets?.toLong() ?: 0)
				server.coresPerSocket = coresPerSocket
				server.resourcePool = cluster
				if (serverName && server.name != serverName)
					server.name = serverName
				server.computeServerType = new ComputeServerType(code: 'nutanix-prism-unmanaged')
				//set extra config
				workload.workloadType = new WorkloadType(code: 'nutanix-prism-provision-provider-1.0"')
				//find the zone in case different than selected?
				//find a matching image and configure agent etc...
				def sourceImageId = extractSourceImageId(values, isV2Schema)

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
				def tags = getAllTags(server.cloud)
				def vmTags = isV2Schema ? resolveV2CategoryTags(values?.categories) : values?.categories?.collect {"${it.name}:${it.value}"}
				def existingTags = server.metadata
				def matchFunction = {existingTag, masterTag -> {
					masterTag == existingTag.externalId
				}}
				def tagSyncLists = NutanixPrismSyncUtils.buildSyncLists(existingTags, vmTags, matchFunction)
				//only need to add non-existing tags
				tagSyncLists.addList?.each {
					if (tags[it]) {
						server.metadata += tags[it]
					}
				}
				morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
				morpheusContext.async.workload.save(workload).blockingGet()
			}
			return ServiceResponse.success(response)
		} else {
			return ServiceResponse.error("IaC Provider ${iacProvider} not supported")
		}

	}

	/**
	 * Extracts the VM's private/public IP from a Terraform `nutanix_virtual_machine`(v1)/
	 * `nutanix_virtual_machine_v2` state `values` map. v2's NIC block shape itself changed at
	 * provider v2.4.1 (new `nic_network_info` nested shape vs. the deprecated top-level
	 * `network_info`) - both are tried, new-style first, so this keeps working across the range of
	 * v2 provider versions without needing a plugin release every time Nutanix bumps the provider.
	 */
	String extractIpAddress(Map values, boolean isV2Schema) {
		if (isV2Schema) {
			def nic = values?.nics?.getAt(0)
			def ip = nic?.nic_network_info?.virtual_ethernet_nic_network_info?.ipv4_config?.ip_address?.value
			if (!ip) {
				ip = nic?.network_info?.ipv4_config?.ip_address?.value
			}
			return ip
		} else {
			def ip = values?.nic_list?.getAt(0)?.ip_endpoint_list?.getAt(0)?.ip
			if (!ip) {
				ip = values?.nic_list_status?.getAt(0)?.ip_endpoint_list?.getAt(0)?.ip
			}
			return ip
		}
	}

	/**
	 * Resolves the source image's external ID from a Terraform `nutanix_virtual_machine`(v1)/
	 * `nutanix_virtual_machine_v2` state `values` map's disk list. v1's `disk_list[].data_source_reference`
	 * is a flat `{kind, uuid}` pair; v2's `disks[].backing_info.vm_disk.data_source.reference` is a
	 * nested oneof (`image_reference` vs `vm_disk_reference`).
	 */
	String extractSourceImageId(Map values, boolean isV2Schema) {
		if (isV2Schema) {
			def disk = values?.disks?.find { it?.backing_info?.vm_disk?.data_source?.reference?.image_reference?.image_ext_id }
			return disk?.backing_info?.vm_disk?.data_source?.reference?.image_reference?.image_ext_id
		} else {
			def disk = values?.disk_list?.find { it?.data_source_reference?.kind == "image" }
			return disk?.data_source_reference?.uuid
		}
	}

	/**
	 * v2 `categories` state entries are category-entity references (`ext_id` only), unlike v1's
	 * inline `name`/`value` string pairs. Now that CategoriesSync stores each synced category's
	 * real V4 `extId` as `MetadataTag.externalId` (see CategoriesSync.migrateLegacyCategories),
	 * matching v2 tags is as simple as collecting the `ext_id` values directly - no separate Prism
	 * Central lookup is needed, since `getAllTags`'s map is already keyed the same way.
	 */
	List<String> resolveV2CategoryTags(List categories) {
		return categories?.findResults { it?.ext_id } ?: []
	}

	Map getAllTags(cloud) {
		def tags = morpheusContext.async.metadataTag.listIdentityProjections(new DataQuery().withFilters([
			new DataFilter("refType", "ComputeZone"),
			new DataFilter("refId", cloud.id),
		])).toMap {it.externalId}.blockingGet()
		tags
	}

}
