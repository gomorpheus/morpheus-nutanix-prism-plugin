package com.morpheusdata.nutanix.prism.plugin

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.ContainerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.Process
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.ProxyConfiguration
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.Snapshot
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.SnapshotIdentityProjection
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.NetworkConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismSyncUtils
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.request.UpdateModel
import com.morpheusdata.response.HostResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.utils.URIBuilder

import java.util.concurrent.TimeUnit

@Slf4j
class NutanixPrismProvisionProvider extends AbstractProvisionProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext

	NutanixPrismProvisionProvider(NutanixPrismPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"nutanix-prism-circular.svg", darkPath: "nutanix-prism-circular-dark.svg")
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		def options = []
		options << new OptionType(
			name: 'skip agent install',
			code: 'provisionType.nutanixPrism.noAgent',
			category: 'provisionType.nutanixPrism',
			inputType: OptionType.InputType.CHECKBOX,
			fieldName: 'noAgent',
			fieldContext: 'config',
			fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
			fieldLabel: 'Skip Agent Install',
			fieldGroup:'Advanced Options',
			displayOrder: 4,
			required: false,
			enabled: true,
			editable:false,
			global:false,
			placeHolder:null,
			helpBlock:'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
			defaultValue:null,
			custom:false,
			fieldClass:null
		)

		options << new OptionType([
			name : 'cluster',
			code : 'nutanix-prism-provision-cluster',
			fieldName : 'clusterName',
			fieldContext : 'config',
			fieldLabel : 'Cluster',
			required : true,
			inputType : OptionType.InputType.SELECT,
			displayOrder : 101,
			optionSource: 'nutanixPrismCluster'

		])

		options << new OptionType([
			name : 'categories',
			code : 'nutanix-prism-provision-categories',
			fieldName : 'categories',
			fieldContext : 'config',
			fieldLabel : 'Categories',
			inputType : OptionType.InputType.MULTI_SELECT,
			displayOrder : 105,
			optionSource: 'nutanixPrismCategories'

		])

		return options
	}

	@Override
	Collection<OptionType> getNodeOptionTypes() {
		OptionType imageOption = new OptionType([
				name : 'image',
				code : 'nutanix-prism-node-image',
				fieldName : 'virtualImage.id',
				fieldContext : 'domain',
				fieldLabel : 'Image',
				inputType : OptionType.InputType.SELECT,
				displayOrder : 100,
				required : false,
				optionSource : 'nutanixPrismNodeImage'
		])
		OptionType logFolder = new OptionType([
			name : 'mountLogs',
			code : 'nutanix-prism-node-log-folder',
			fieldName : 'mountLogs',
			fieldContext : 'domain',
			fieldLabel : 'Log Folder',
			inputType : OptionType.InputType.TEXT,
			displayOrder : 101,
			required : false,
		])
		OptionType configFolder = new OptionType([
			name : 'mountConfig',
			code : 'nutanix-prism-node-config-folder',
			fieldName : 'mountConfig',
			fieldContext : 'domain',
			fieldLabel : 'Config Folder',
			inputType : OptionType.InputType.TEXT,
			displayOrder : 102,
			required : false,
		])
		OptionType deployFolder = new OptionType([
			name : 'mountData',
			code : 'nutanix-prism-node-deploy-folder',
			fieldName : 'mountData',
			fieldContext : 'domain',
			fieldLabel : 'Deploy Folder',
			inputType : OptionType.InputType.TEXT,
			displayOrder : 103,
			helpText: '(Optional) If using deployment services, this mount point will be replaced with the contents of said deployments.',
			required : false,
		])
		OptionType checkTypeCode = new OptionType([
			name : 'checkTypeCode',
			code : 'nutanix-prism-node-check-type-code',
			fieldName : 'checkTypeCode',
			fieldContext : 'domain',
			fieldLabel : 'Check Type Code',
			inputType : OptionType.InputType.HIDDEN,
			defaultValue: 'vmCheck',
			displayOrder : 104,
			required : false,
		])
		OptionType statTypeCode = new OptionType([
			name : 'statTypeCode',
			code : 'nutanix-prism-node-stat-type-code',
			fieldName : 'statTypeCode',
			fieldContext : 'domain',
			fieldLabel : 'Stat Type Code',
			inputType : OptionType.InputType.HIDDEN,
			defaultValue: 'vm',
			displayOrder : 105,
			required : false,
		])
		OptionType logTypeCode = new OptionType([
			name : 'logTypeCode',
			code : 'nutanix-prism-node-log-type-code',
			fieldName : 'logTypeCode',
			fieldContext : 'domain',
			fieldLabel : 'Log Type Code',
			inputType : OptionType.InputType.HIDDEN,
			defaultValue: 'vm',
			displayOrder : 106,
			required : false,
		])
		OptionType showServerLogs = new OptionType([
			name : 'showServerLogs',
			code : 'nutanix-prism-node-show-server-logs',
			fieldName : 'showServerLogs',
			fieldContext : 'domain',
			fieldLabel : 'Show Server Logs',
			inputType : OptionType.InputType.HIDDEN,
			defaultValue: true,
			displayOrder : 107,
			required : false,
		])
		return [imageOption, logFolder, configFolder, deployFolder, checkTypeCode, statTypeCode, showServerLogs, logTypeCode]
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		def servicePlans = []
		servicePlans << new ServicePlan([code:'nutanix-prism-vm-512', name:'1 vCPU, 512MB Memory', description:'1 vCPU, 512MB Memory', sortOrder:0,
				maxStorage:10l * 1024l * 1024l * 1024l, maxMemory: 1l * 512l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-vm-1024', name:'1 vCPU, 1GB Memory', description:'1 vCPU, 1GB Memory', sortOrder:1,
				maxStorage: 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-vm-2048', name:'1 vCPU, 2GB Memory', description:'1 vCPU, 2GB Memory', sortOrder:2,
				maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-vm-4096', name:'1 vCPU, 4GB Memory', description:'1 vCPU, 4GB Memory', sortOrder:3,
				maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-vm-8192', name:'2 vCPU, 8GB Memory', description:'2 vCPU, 8GB Memory', sortOrder:4,
				maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCores:2, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-vm-16384', name:'2 vCPU, 16GB Memory', description:'2 vCPU, 16GB Memory', sortOrder:5,
				maxStorage: 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCores:2,
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-vm-24576', name:'4 vCPU, 24GB Memory', description:'4 vCPU, 24GB Memory', sortOrder:6,
				maxStorage: 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCores:4, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-vm-32768', name:'4 vCPU, 32GB Memory', description:'4 vCPU, 32GB Memory', sortOrder:7,
				maxStorage: 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCores:4, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-internal-custom', editable:false, name:'Nutanix Custom', description:'Nutanix Custom', sortOrder:0,
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true, customCpu: true, customCores: true, customMaxMemory: true, deletable: false, provisionable: false,
				maxStorage:0l, maxMemory: 0l,  maxCpu:0])
		servicePlans
	}

	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		ComputeServerInterfaceType computeServerInterface = new ComputeServerInterfaceType([
				code:'nutanix-prism-normal-nic',
				externalId:'NORMAL_NIC',
				name:'Nutanix Prism Central Normal NIC',
				defaultType: true,
				enabled: true,
				displayOrder:1
		])
		[computeServerInterface]
	}

	@Override
	Boolean hasSnapshots() {
		return true
	}

	@Override
	Boolean hasDatastores() {
		return true
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasPlanTagMatch() {
		return true
	}

	@Override
	Integer getMaxNetworks() {
		return null
	}

	@Override
	Boolean networksScopedToPools() {
		return true
	}

	@Override
	String getNodeFormat() {
		return "vm"
	}

//	@Override
//	String getDeployTargetService() {
//		return "vmDeployTargetService"
//	}

	@Override
	Boolean hasCloneTemplate() {
		return true
	}

	@Override
	ServiceResponse getNoVNCConsoleUrl(ComputeServer server) {
		Map authConfig = plugin.getAuthConfig(server.cloud)
		def consoleInfo = NutanixPrismComputeUtility.getVMConsoleUrl(authConfig, server.externalId, server?.resourcePool?.externalId)
		return consoleInfo
	}

	@Override
	ServiceResponse enableConsoleAccess(ComputeServer server) {
		server.consoleType = 'vnc'
		return updateServerHost(server)

	}

	@Override
	ServiceResponse updateServerHost(ComputeServer server) {
		server.consoleHost = new URIBuilder(plugin.getAuthConfig(server.cloud)?.apiUrl)?.getHost()
		saveAndGet(server)
		return ServiceResponse.success(server)
	}

	@Override
	ServiceResponse createSnapshot(ComputeServer server, Map opts) {
		HttpApiClient client = new HttpApiClient()

		Map authConfig = plugin.getAuthConfig(server.cloud)
		def snapshotName = opts.snapshotName ?: "${server.name}.${System.currentTimeMillis()}"
		log.debug("Executing Nutanix Prism Central snapshot for ${server?.name}")
		def snapshotResult = NutanixPrismComputeUtility.createSnapshot(client, authConfig, server?.resourcePool?.externalId, server.externalId, snapshotName)
		def taskId = snapshotResult?.data?.task_uuid
		def taskResults = NutanixPrismComputeUtility.checkTaskReady(client, authConfig, taskId)
		log.debug("Snapshot results: ${taskResults}")
		if(taskResults.success) {
			def snapshotUuid = taskResults?.data?.entity_reference_list?.find { it.kind == 'snapshot'}.uuid
			if(snapshotUuid) {
				def rawSnapshot = NutanixPrismComputeUtility.getSnapshot(client, authConfig, server?.resourcePool?.externalId, snapshotUuid)
				Date createdDate = null
				if(rawSnapshot?.data?.created_time) {
					long milliseconds = TimeUnit.MICROSECONDS.toMillis(rawSnapshot?.data?.created_time)
					createdDate = new Date(milliseconds)
				}
				def snapshotConfig = [
						account        : server.cloud.owner,
						name           : rawSnapshot?.data?.snapshot_name,
						externalId     : rawSnapshot?.data?.uuid,
						cloud          : server.cloud,
						snapshotCreated: createdDate,
						currentlyActive: true,
						description    : opts.description
				]
				def add = new Snapshot(snapshotConfig)
				Snapshot savedSnapshot = morpheusContext.snapshot.create(add).blockingGet()
				if (!savedSnapshot) {
					return ServiceResponse.error("Error saving snapshot")
				} else {
					morpheusContext.snapshot.addSnapshot(savedSnapshot, server).blockingGet()
				}
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error("Error fetching snapshot after creation", null, taskResults)
			}
		} else {
			return ServiceResponse.error("Error creating snapshot", null, taskResults)
		}

	}

	@Override
	ServiceResponse deleteSnapshots(ComputeServer server, Map opts) {
		HttpApiClient client = new HttpApiClient()
		Map authConfig = plugin.getAuthConfig(server.cloud)
		log.debug("Deleting Nutanix Prism Central snapshots for server ${server.name}")
		List<SnapshotIdentityProjection> snapshots = server.snapshots
		Boolean success = true
		for(int i = 0; i < snapshots.size(); i++) {
			SnapshotIdentityProjection snapshot = snapshots[i]
			def snapshotResult = NutanixPrismComputeUtility.deleteSnapshot(client, authConfig, server?.resourcePool?.externalId, snapshot.externalId)
			def taskId = snapshotResult?.data?.task_uuid
			def taskResults = NutanixPrismComputeUtility.checkTaskReady(client, authConfig, taskId)
			success &= taskResults.success
			if(!taskResults.success) {
				log.error("API error deleting snapshot ${taskResults}")
			}
		}
		if(success) {
			return ServiceResponse.success()
		} else {
			return ServiceResponse.error("Not all snapshots successfully deleted")
		}
	}

	@Override
	ServiceResponse deleteSnapshot(Snapshot snapshot, Map opts) {
		HttpApiClient client = new HttpApiClient()
		ComputeServer server = morpheusContext.computeServer.get(opts.serverId).blockingGet()
		Map authConfig = plugin.getAuthConfig(server.cloud)
		log.debug("Deleting Nutanix Prism Central Snapshot ${snapshot.name}")
		def snapshotResult = NutanixPrismComputeUtility.deleteSnapshot(client, authConfig, server?.resourcePool?.externalId, snapshot.externalId)
		def taskId = snapshotResult?.data?.task_uuid
		def taskResults = NutanixPrismComputeUtility.checkTaskReady(client, authConfig, taskId)
		log.debug("Snapshot delete results : ${taskResults}")
		if(taskResults.success) {
			return ServiceResponse.success()
		} else {
			return ServiceResponse.error("API error deleting snapshot", null, taskResults)
		}
	}

	@Override
	ServiceResponse revertSnapshot(ComputeServer server, Snapshot snapshot, Map opts) {
		HttpApiClient client = new HttpApiClient()
		Map authConfig = plugin.getAuthConfig(server.cloud)
		log.debug("Reverting Nutanix Prism Central Snapshot ${snapshot.name}")
		def snapshotResult = NutanixPrismComputeUtility.restoreSnapshot(client, authConfig, server?.resourcePool?.externalId, server.externalId, snapshot.externalId)
		def taskId = snapshotResult?.data?.task_uuid
		def taskResults = NutanixPrismComputeUtility.checkTaskReady(client, authConfig, taskId)
		log.debug("Snapshot revert results : ${taskResults}")
		if(taskResults.success) {
			return ServiceResponse.success()
		} else {
			return ServiceResponse.error("API error reverting snapshot", null, taskResults)
		}
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.debug("validateWorkload: ${opts}")
		ServiceResponse rtn = new ServiceResponse(true, null, [:], null)
		try {
			Cloud cloud = morpheusContext.cloud.getCloudById(opts.zoneId?.toLong()).blockingGet()
			def apiInfo = [:]
			if(opts.hostId) {
				apiInfo = plugin.getAuthConfig(cloud)
			}

			def validateTemplate = opts.template != null
			NutanixPrismOptionSourceProvider optionSourceProvider = plugin.getProviderByCode('nutanix-prism-option-source')
			def validationResults = NutanixPrismComputeUtility.validateServerConfig(morpheusContext, apiInfo.apiUrl, apiInfo.apiUsername, apiInfo.apiPassword,
					[validateTemplate:validateTemplate] + opts)
			if(!validationResults.success) {
				validationResults.errors?.each { it ->
					rtn.addError(it.field, it.msg)
				}
			}

		} catch(e) {
			log.error("validateWorkload error: ${e}", e)
		}
		return rtn
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse validateInstance(Instance instance, Map opts) {
		log.debug "validateInstance: ${instance} ${opts}"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse validateDockerHost(ComputeServer server, Map opts) {
		log.debug "validateDockerHost: ${server} ${opts}"
		return ServiceResponse.success()
	}

	@Override
	public ServiceResponse prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "prepareWorkload: ${workload} ${workloadRequest} ${opts}"

		def rtn = [success: false, msg: null]
		try {
			Long virtualImageId = workload.getConfigProperty('imageId')?.toLong() ?: workload?.workloadType?.virtualImage?.id
			if(!virtualImageId) {
				rtn.msg = "No virtual image selected"
			} else {
				VirtualImage virtualImage
				try {
					virtualImage = morpheusContext.virtualImage.get(virtualImageId).blockingGet()
				} catch(e) {
					log.error "error in get image: ${e}"
				}
				if(!virtualImage) {
					rtn.msg = "No virtual image found for ${virtualImageId}"
				} else {
					workload.server.sourceImage = virtualImage
					saveAndGet(workload.server)
					rtn.success = true
				}
			}
		} catch(e) {
			rtn.msg = "Error in PrepareWorkload: ${e}"
			log.error "${rtn.msg}, ${e}", e

		}
		if(!rtn.success) {
			log.error "prepareWorkload: error - ${rtn.msg}"
		}
		new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	@Override
	ServiceResponse<WorkloadResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "runWorkload ${workload.configs} ${opts}"

		def rtn = [success:false]

		HttpApiClient client
		WorkloadResponse workloadResponse = new WorkloadResponse(success: true, installAgent: false)
		ComputeServer server = workload.server
		try {
			Cloud cloud = server.cloud
			VirtualImage virtualImage = server.sourceImage
			Map authConfig = plugin.getAuthConfig(cloud)

			client = new HttpApiClient()
			client.networkProxy = buildNetworkProxy(workloadRequest.proxyConfiguration)

			def imageExternalId = getOrUploadImage(client, authConfig, cloud, virtualImage)

			def runConfig = buildWorkloadRunConfig(workload, workloadRequest, imageExternalId, opts)
			runConfig.imageExternalId = imageExternalId
			runConfig.virtualImageId = server.sourceImage?.id
			runConfig.userConfig = workloadRequest.usersConfiguration
			if(imageExternalId) {

				server.sshUsername = runConfig.userConfig.sshUsername
				server.sshPassword = runConfig.userConfig.sshPassword
				workloadResponse.createUsers = runConfig.userConfig.createUsers

				runVirtualMachine(cloud, runConfig, workloadResponse,  workloadRequest, null)

			} else {
				server.statusMessage = 'Error on vm image'
			}

			if (workloadResponse.success != true) {
				return new ServiceResponse(success: false, msg: workloadResponse.message ?: 'vm config error', error: workloadResponse.message, data: workloadResponse)
			} else {
				return new ServiceResponse<WorkloadResponse>(success: true, data: workloadResponse)
			}
		} catch(e) {
			log.error "runWorkload: ${e}", e
			workloadResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: workloadResponse)
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}
	}

	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		def rtn = [success: true, msg: null]
		log.debug "finalizeWorkload: ${workload?.id}"
		try {
			ComputeServer server = workload.server
			Cloud cloud = server.cloud

			def authConfig = plugin.getAuthConfig(cloud)
			def vmId = server.externalId
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def serverDetails = NutanixPrismComputeUtility.getVm(client, authConfig, vmId)
			//check if ip changed and update
			def serverResource = serverDetails?.data?.spec?.resources
			def ipAddress = null
			if(serverDetails.success == true && serverResource.nic_list?.size() > 0 && serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{NutanixPrismComputeUtility.checkIpv4Ip(it)} ) {
				ipAddress = serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{NutanixPrismComputeUtility.checkIpv4Ip(it)}
			}
			def privateIp = ipAddress
			def publicIp = ipAddress
			if(server.internalIp != privateIp) {
				server.internalIp = privateIp
				server.externalIp = publicIp
				morpheusContext.computeServer.save([server]).blockingGet()
			}
		} catch(e) {
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error "Error in finalizeWorkload: ${e}", e
		}
		return new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	@Override
	ServiceResponse prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def rtn = [success: false, msg: null]
		try {
			VirtualImage virtualImage
			Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.computeTypeSet.get(computeTypeSetId).blockingGet()
				if(computeTypeSet.containerType) {
					ContainerType containerType = morpheus.containerType.get(computeTypeSet.containerType.id).blockingGet()
					virtualImage = containerType.virtualImage
				}
			}
			if(!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error("${rtn.msg}, ${e}", e)

		}
		new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	@Override
	ServiceResponse<HostResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost: ${server} ${hostRequest} ${opts}")

		def rtn = [success:false]

		HostResponse hostResponse = new HostResponse(success: true, installAgent: false)

		HttpApiClient client
		try {
			Cloud cloud = server.cloud
			VirtualImage virtualImage = server.sourceImage
			Map authConfig = plugin.getAuthConfig(cloud)

			client = new HttpApiClient()
			client.networkProxy = buildNetworkProxy(hostRequest.proxyConfiguration)

			def imageExternalId = getOrUploadImage(client, authConfig, cloud, virtualImage)

			def runConfig = buildHostRunConfig(server, hostRequest, imageExternalId, opts)


			runConfig.imageExternalId = imageExternalId
			runConfig.virtualImageId = server.sourceImage?.id
			runConfig.userConfig = hostRequest.usersConfiguration

			WorkloadResponse workloadResponse = new WorkloadResponse(success: true, installAgent: false)
			if(imageExternalId) {

				server.sshUsername = runConfig.userConfig.sshUsername
				server.sshPassword = runConfig.userConfig.sshPassword
				workloadResponse.createUsers = runConfig.userConfig.createUsers

				runVirtualMachine(cloud, runConfig, workloadResponse, null, hostRequest)

			} else {
				server.statusMessage = 'Error on vm image'
			}

			hostResponse = workloadResponseToHostResponse(workloadResponse)

			if (hostResponse.success != true) {
				return new ServiceResponse(success: false, msg: hostResponse.message ?: 'vm config error', error: hostResponse.message, data: hostResponse)
			} else {
				return new ServiceResponse<WorkloadResponse>(success: true, data: hostResponse)
			}
		} catch(e) {
			log.error "runHost: ${e}", e
			hostResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: hostResponse)
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}
	}


	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		def rtn = [success: true, msg: null]
		log.debug "finalizeHost: ${server?.id}"
		try {
			Cloud cloud = server.cloud

			def authConfig = plugin.getAuthConfig(cloud)
			def vmId = server.externalId
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def serverDetails = NutanixPrismComputeUtility.getVm(client, authConfig, vmId)
			//check if ip changed and update
			def serverResource = serverDetails?.data?.spec?.resources
			def ipAddress = null
			if(serverDetails.success == true && serverResource.nic_list?.size() > 0 && serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{NutanixPrismComputeUtility.checkIpv4Ip(it)} ) {
				ipAddress = serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{NutanixPrismComputeUtility.checkIpv4Ip(it)}
			}
			def privateIp = ipAddress
			def publicIp = ipAddress
			if(server.internalIp != privateIp) {
				server.internalIp = privateIp
				server.externalIp = publicIp
				morpheusContext.computeServer.save([server]).blockingGet()
			}
		} catch(e) {
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error "Error in finalizeHost: ${e}", e
		}
		return new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	@Override
	ServiceResponse stopWorkload(Workload workload) {
		log.debug "stopWorkload: ${workload}"
		if(workload.server?.externalId) {
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def authConfig = plugin.getAuthConfig(cloud)
			def vmResource = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, server.externalId)
			def stopResults = NutanixPrismComputeUtility.stopVm(client, authConfig, server.externalId, vmResource.data)
			log.debug("stopResults: ${stopResults}")
			if(stopResults.success == true) {
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error(stopResults.msg ?: 'Error stopping VM')
			}
		} else {
			ServiceResponse.error('vm not found')
		}
	}

	@Override
	ServiceResponse startWorkload(Workload workload) {
		log.debug "startWorkload: ${workload}"
		if(workload.server?.externalId) {
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def authConfig = plugin.getAuthConfig(cloud)
			def vmResource = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, server.externalId)
			def startResults = NutanixPrismComputeUtility.startVm(client, authConfig, server.externalId, vmResource.data)
			log.debug("startResults: ${startResults}")
			if(startResults.success == true) {
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error(startResults.msg ?: 'Error starting VM')
			}
		} else {
			ServiceResponse.error('vm not found')
		}
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		log.debug("startServer: ${computeServer}")
		def rtn = [success:false]
		try {
			if(computeServer.managed == true || computeServer.computeServerType?.controlPower) {
				Cloud cloud = computeServer.cloud
				HttpApiClient client = new HttpApiClient()
				client.networkProxy = cloud.apiProxy
				def authConfig = plugin.getAuthConfig(cloud)
				def vmResource = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, computeServer.externalId)
				def startResults = NutanixPrismComputeUtility.startVm(client, authConfig, computeServer.externalId, vmResource.data)
				if(startResults.success == true) {
					rtn.success = true
				}
			} else {
				log.info("startServer - ignoring request for unmanaged instance")
			}
		} catch(e) {
			rtn.msg = "Error starting server: ${e.message}"
			log.error("startServer error: ${e}", e)
		}
		return new ServiceResponse(rtn)
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		log.debug("stopServer: ${computeServer}")
		def rtn = [success:false]
		try {
			if(computeServer.managed == true || computeServer.computeServerType?.controlPower) {
				Cloud cloud = computeServer.cloud
				HttpApiClient client = new HttpApiClient()
				client.networkProxy = cloud.apiProxy
				def authConfig = plugin.getAuthConfig(cloud)
				def vmResource = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, computeServer.externalId)
				def stopResults = NutanixPrismComputeUtility.stopVm(client, authConfig, computeServer.externalId, vmResource.data)
				if(stopResults.success == true) {
					rtn.success = true
				}
			} else {
				log.info("stopServer - ignoring request for unmanaged instance")
			}
		} catch(e) {
			rtn.msg = "Error stopping server: ${e.message}"
			log.error("stopServer error: ${e}", e)
		}
		return new ServiceResponse(rtn)
	}

	ServiceResponse deleteServer(ComputeServer computeServer) {
		log.debug("deleteServer: ${computeServer}")
		def rtn = [success:false]
		try {
			if(computeServer.managed == true || computeServer.computeServerType?.controlPower) {
				Cloud cloud = computeServer.cloud
				HttpApiClient client = new HttpApiClient()
				client.networkProxy = cloud.apiProxy
				def authConfig = plugin.getAuthConfig(cloud)
				def vmResource = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, computeServer.externalId)
				def removeResults = NutanixPrismComputeUtility.destroyVm(client, authConfig, computeServer.externalId)
				if(removeResults.success == true) {
					rtn.success = true
				}
			} else {
				log.info("deleteServer - ignoring request for unmanaged instance")
			}
		} catch(e) {
			rtn.msg = "Error deleting server: ${e.message}"
			log.error("deleteServer error: ${e}", e)
		}
		return new ServiceResponse(rtn)
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts){
		log.debug "removeWorkload: ${workload} ${opts}"
		if(workload.server?.externalId) {
			stopWorkload(workload)
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def authConfig = plugin.getAuthConfig(cloud)
			def vmResource = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, server.externalId)
			def removeResults = NutanixPrismComputeUtility.destroyVm(client, authConfig, server.externalId)
			if(removeResults.success == true) {
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error('Failed to remove vm')
			}
		} else {
			return ServiceResponse.success()
		}
	}


	@Override
	ServiceResponse restartWorkload(Workload workload) {
		log.debug 'restartWorkload'
		ServiceResponse stopResult = stopWorkload(workload)
		if (stopResult.success) {
			return startWorkload(workload)
		}
		stopResult
	}


	@Override
	ServiceResponse<WorkloadResponse> getServerDetails(ComputeServer server) {
		WorkloadResponse rtn = new WorkloadResponse()
		def serverUuid = server.externalId
		if(server && server.uuid) {
			Cloud cloud = server.cloud
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def authConfig = plugin.getAuthConfig(cloud)
			Map serverDetails = NutanixPrismComputeUtility.checkServerReady(client, authConfig, serverUuid)
			if(serverDetails.success && serverDetails.virtualMachine) {
				rtn.externalId = serverUuid
				rtn.success = serverDetails.success
				rtn.publicIp = serverDetails.ipAddress
				rtn.privateIp = serverDetails.ipAddress
				rtn.hostname = serverDetails.name
				return ServiceResponse.success(rtn)

			} else {
				return ServiceResponse.error("Server not ready/does not exist")
			}
		} else {
			return ServiceResponse.error("Could not find server uuid")
		}
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		def server = morpheusContext.computeServer.get(workload.server.id).blockingGet()
		if(server) {
			return internalResizeServer(server, resizeRequest)
		} else {
			return ServiceResponse.error("No server provided")
		}
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		return internalResizeServer(server, resizeRequest)
	}

	private internalResizeServer(ComputeServer server, ResizeRequest resizeRequest) {
		ServiceResponse rtn = ServiceResponse.success()
		try {
			Cloud cloud = server.cloud

			def authConfig = plugin.getAuthConfig(cloud)

			def vmId = server.externalId
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy


			deleteSnapshots(server, [:])

			def serverDetails = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, vmId)
			def vmBody = serverDetails?.data

			def maxCores = resizeRequest.maxCores ?: 1
			def coresPerSocket = resizeRequest.coresPerSocket ?: 1
			if(coresPerSocket == 0) {
				coresPerSocket = 1
			}

			def updatedResources = [
					maxMemory: resizeRequest.maxMemory.div(ComputeUtility.ONE_MEGABYTE),
					maxCores: maxCores,
					coresPerSocket: coresPerSocket,
					numSockets: maxCores.toInteger() / coresPerSocket.toInteger()

			]
			def updateResourcesResult = NutanixPrismComputeUtility.adjustVmResources(client, authConfig, vmId, updatedResources, vmBody)


			//disks
			//skip controllers for now
			//remove disks to delete
			def volumesToDelete = resizeRequest.volumesDelete?.collect {it.externalId}
			if(volumesToDelete.size() > 0) {
				serverDetails = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, vmId)
				vmBody = serverDetails?.data
				def newDiskList = vmBody.spec?.resources?.disk_list?.findAll { !volumesToDelete.contains(it.uuid) }
				vmBody.spec?.resources?.disk_list = newDiskList
				def deleteResults = NutanixPrismComputeUtility.updateVm(client, authConfig, vmId, vmBody)
				if(deleteResults.success == true) {
					log.info("resize volume delete complete: ${deleteResults.success}")
					resizeRequest.volumesDelete?.each { StorageVolume volume ->
						morpheusContext.storageVolume.remove([volume], server, true).blockingGet()
					}
				}
			}

			//update existing disks
			resizeRequest.volumesUpdate?.each { UpdateModel<StorageVolume> volumeUpdate ->
				StorageVolume existing = volumeUpdate.existingModel
				Map updateProps = volumeUpdate.updateProps
				log.info("resizing vm storage: {}", volumeUpdate)
				if (updateProps.maxStorage > existing.maxStorage) {
					serverDetails = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, vmId)
					vmBody = serverDetails?.data
					def newDiskList = vmBody.spec?.resources?.disk_list
					def diskMap = newDiskList.find{it.uuid == existing.externalId}
					diskMap.disk_size_bytes = updateProps.maxStorage
					diskMap.remove("disk_size_mib")
					def result = NutanixPrismComputeUtility.updateVm(client, authConfig, vmId, vmBody)
					if (result.success) {
						existing.maxStorage = updateProps.maxStorage
						morpheusContext.storageVolume.save([existing]).blockingGet()
					} else {
						rtn.setError(result.msg ?: "Failed to expand Disk: ${existing.name}")
						log.warn("error resizing disk: ${result.msg}")
					}
				}
			}

			//add new disks
			def datastoreIds = []
			def storageVolumeTypes = [:]
			resizeRequest.volumesAdd?.each { Map volumeAdd ->
				datastoreIds << volumeAdd.datastoreId.toLong()
				def storageVolumeTypeId = volumeAdd.storageType.toLong()
				if(!storageVolumeTypes[storageVolumeTypeId]) {
					storageVolumeTypes[storageVolumeTypeId] = morpheusContext.storageVolume.storageVolumeType.get(storageVolumeTypeId).blockingGet()
				}

			}
			datastoreIds = datastoreIds.unique()
			def datastores = morpheusContext.cloud.datastore.listById(datastoreIds).toMap {it.id.toLong()}.blockingGet()
			resizeRequest.volumesAdd?.each { Map volumeAdd ->
				serverDetails = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, vmId)
				vmBody = serverDetails?.data
				log.info("resizing vm adding storage: {}", volumeAdd)
				if (!volumeAdd.maxStorage) {
					volumeAdd.maxStorage = volumeAdd.size ? (volumeAdd.size.toDouble() * ComputeUtility.ONE_GIGABYTE).toLong() : 0
				}
				def storageVolumeType = storageVolumeTypes[volumeAdd.storageType.toLong()]
				def datastore = datastores[volumeAdd.datastoreId.toLong()]
				def targetIndex = vmBody.spec?.resources?.disk_list.size()
				if(targetIndex != null) {
					//account for ide.0
					targetIndex--
				} else {
					targetIndex = 0
				}
				def newDiskList = vmBody.spec?.resources?.disk_list

				def diskConfig = [
						device_properties: [
								device_type: "DISK",
								disk_address: [
										adapter_type: storageVolumeType.name.toUpperCase(),
										device_index: targetIndex
								],
						],
						disk_size_bytes: volumeAdd.maxStorage,
						storage_config: [
								storage_container_reference: [
										uuid: datastore.externalId,
										name: datastore.name,
										kind: "storage_container",
								]
						]
				]
				newDiskList << diskConfig
				vmBody.spec.resources.disk_list = newDiskList
				def addDiskResults = NutanixPrismComputeUtility.updateVm(client, authConfig, vmId, vmBody)
				if(addDiskResults.success) {
					//wait for operation to complete
					serverDetails = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, vmId)
					vmBody = serverDetails?.data
					def newDisk = vmBody.spec.resources.disk_list.find {it.device_properties.disk_address.adapter_type == storageVolumeType.name.toUpperCase() && it.device_properties.disk_address.device_index == targetIndex}
					def newVolume = NutanixPrismSyncUtils.buildStorageVolume(server.account, server, volumeAdd, targetIndex)
					newVolume.externalId = newDisk.uuid
					newVolume.type = new StorageVolumeType(id: volumeAdd.storageType.toLong())
					morpheusContext.storageVolume.create([newVolume], server).blockingGet()
					// Need to refetch the server
					server = morpheusContext.computeServer.get(server.id).blockingGet()
				} else {
					//do stuff here to bubble up results
					rtn.setError("error adding disk: ${addDiskResults?.msg}")
					log.warn("error adding disk: ${addDiskResults}")
				}
			}


			//networks

			def networksToDelete = resizeRequest.interfacesDelete?.collect {it.externalId}
			if(networksToDelete.size() > 0) {
				serverDetails = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, vmId)
				vmBody = serverDetails?.data
				def newNicList = vmBody.spec?.resources?.nic_list?.findAll { !networksToDelete.contains(it.uuid) }
				vmBody.spec?.resources?.nic_list = newNicList
				def deleteResults = NutanixPrismComputeUtility.updateVm(client, authConfig, vmId, vmBody)
				if(deleteResults.success == true) {
					log.info("resize network delete complete: ${deleteResults.success}")
					resizeRequest.interfacesDelete?.each { ComputeServerInterface networkDelete ->
						morpheusContext.computeServer.computeServerInterface.remove([networkDelete]).blockingGet()
					}
				}
			}
//
			//TODO update support
//			resizeRequest?.interfacesUpdate?.eachWithIndex { UpdateModel<ComputeServerInterface> networkUpdate, index ->
//			}
//
			resizeRequest.interfacesAdd?.each{ Map networkAdd ->
				serverDetails = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, vmId)
				vmBody = serverDetails?.data
				def newIndex = networkAdd?.network?.isPrimary ? 0 : server.interfaces?.size()

				Network newNetwork = morpheusContext.network.listById([networkAdd.network.id.toLong()]).firstOrError().blockingGet()

				def networkConfig = [
						is_connected    : true,
						subnet_reference: [
								uuid: newNetwork.externalId,
								name: newNetwork.name,
								kind: "subnet"
						]
				]
				if(networkAdd.ipAddress) {
					networkConfig["ip_endpoint_list"] = [
							[
							"ip": networkAdd.ipAddress
						]
					]
				}
				def newNicList = vmBody.spec?.resources?.nic_list
				def oldNicList = newNicList.collect {it.uuid}
				newNicList << networkConfig


				vmBody.spec.resources.nic_list = newNicList
				def networkResults = NutanixPrismComputeUtility.updateVm(client, authConfig, vmId, vmBody)
				if(networkResults.success) {
					//wait for operation to complete
					serverDetails = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, vmId)
					vmBody = serverDetails?.data
					def newNic = vmBody.spec.resources.nic_list.find {!oldNicList.contains(it.uuid)}
					def newInterface = new ComputeServerInterface([
							externalId      : newNic.uuid,
							type            : new ComputeServerInterfaceType(code: 'nutanix-prism-normal-nic'),
							macAddress      : newNic.mac_address,
							name            : "eth${newIndex}",
							ipAddress       : newNic.ip_endpoint_list?.getAt(0)?.ip,
							network         : newNetwork ? new Network(id: newNetwork.id) : null,
							displayOrder    : newIndex,
							primaryInterface: networkAdd?.network?.isPrimary ? true : false
					])
					morpheusContext.computeServer.computeServerInterface.create([newInterface], server).blockingGet()
					// Need to refetch the server
					server = morpheusContext.computeServer.get(server.id).blockingGet()
				} else {
					//do stuff here to bubble up results
					rtn.setError("error adding disk: ${addDiskResults?.msg}")
					log.warn("error adding disk: ${addDiskResults}")
				}

			}
//
		} catch(e) {
			log.error("Unable to resize container: ${e.message}", e)
			rtn.setError("Error resizing workload: ${e}")
		}
		return rtn
	}


	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	HostType getHostType() {
		HostType.vm
	}

	@Override
	Collection<VirtualImage> getVirtualImages() {
		return new ArrayList<VirtualImage>()
	}

	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {
		return new ArrayList<ComputeTypeLayout>()
	}

	@Override
	Boolean canAddVolumes() {
		true
	}

	@Override
	Boolean disableRootDatastore() { return true }

	@Override
	Boolean hasConfigurableSockets() { return true }

	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return plugin
	}

	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	@Override
	Boolean canResizeRootVolume() {
		return true
	}

	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	@Override
	Boolean hasStorageControllers() {
		false
	}

	@Override
	Boolean supportsAutoDatastore() {
		false
	}

	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		getStorageVolumeTypes()
	}

	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		getStorageVolumeTypes()
	}

	private getStorageVolumeTypes() {
		def volumeTypes = []

		volumeTypes << new StorageVolumeType([
			code: 'nutanix-prism-disk-scsi',
			externalId: 'scsi',
			name: 'scsi',
			displayOrder: 1
		])

		volumeTypes << new StorageVolumeType([
			code: 'nutanix-prism-disk-pci',
			externalId: 'pci',
			name: 'pci',
			displayOrder: 2
		])

		volumeTypes << new StorageVolumeType([
			code: 'nutanix-prism-disk-ide',
			externalId: 'ide',
			name: 'ide',
			displayOrder: 3
		])

		volumeTypes << new StorageVolumeType([
			code: 'nutanix-prism-disk-sata',
			externalId: 'sata',
			name: 'sata',
			displayOrder: 0
		])

		volumeTypes
	}

	@Override
	String getCode() {
		return 'nutanix-prism-provision-provider'
	}

	@Override
	String getName() {
		return 'Nutanix Prism Central'
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = morpheusContext.computeServer.save([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return morpheusContext.computeServer.get(server.id).blockingGet()
	}

	private NetworkProxy buildNetworkProxy(ProxyConfiguration proxyConfiguration) {
		NetworkProxy networkProxy
		if(proxyConfiguration) {
			networkProxy.proxyDomain = proxyConfiguration.proxyDomain
			networkProxy.proxyHost = proxyConfiguration.proxyHost
			networkProxy.proxyPassword = proxyConfiguration.proxyPassword
			networkProxy.proxyUser = proxyConfiguration.proxyUser
			networkProxy.proxyPort = proxyConfiguration.proxyPort
			networkProxy.proxyWorkstation = proxyConfiguration.proxyWorkstation
		}
		return networkProxy
	}

	private getOrUploadImage(HttpApiClient client, Map authConfig, Cloud cloud, VirtualImage virtualImage) {
		def imageExternalId
		def lock
		def lockKey = "nutanix.prism.imageupload.${cloud.regionCode}.${virtualImage?.id}".toString()
		try {
			lock = morpheusContext.acquireLock(lockKey, [timeout: 2l * 60l * 1000l, ttl: 2l * 60l * 1000l]).blockingGet()
			//hold up to a 1 hour lock for image upload
			if (virtualImage) {
				VirtualImageLocation virtualImageLocation
				try {
					virtualImageLocation = morpheusContext.virtualImage.location.findVirtualImageLocation(virtualImage.id, cloud.id, cloud.regionCode, null, false).blockingGet()
					if (!virtualImageLocation) {
						imageExternalId = null
					} else {
						imageExternalId = virtualImageLocation.externalId
					}
				} catch (e) {
					log.info "Error in findVirtualImageLocation.. could be not found ${e}", e
				}
				if (imageExternalId) {
					ServiceResponse response = NutanixPrismComputeUtility.getImage(client, authConfig, imageExternalId)
					if (!response.success) {
						imageExternalId = null
					}
				}
			}

			if (!imageExternalId) { //If its userUploaded and still needs uploaded
				// Create the image
				def cloudFiles = morpheusContext.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
				def imageFile = cloudFiles?.find { cloudFile -> cloudFile.name.toLowerCase().endsWith(".qcow2") }
				def contentLength = imageFile?.getContentLength()
				// The url given will be used by Nutanix to download the image.. it will be in a RUNNING status until the download is complete
				// For morpheus images, this is fine as it is publicly accessible. But, for customer uploaded images, need to upload the bytes
				def letNutanixDownloadImage = imageFile?.getURL()?.toString()?.contains('morpheus-images')
				def imageResults = NutanixPrismComputeUtility.createImage(client, authConfig,
					virtualImage.name, 'DISK_IMAGE', letNutanixDownloadImage ? imageFile?.getURL()?.toString() : null)
				if (imageResults.success) {
					imageExternalId = imageResults.data.metadata.uuid
					// Create the VirtualImageLocation before waiting for the upload
					VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
						virtualImage: virtualImage,
						externalId  : imageExternalId,
						imageRegion : cloud.regionCode,
						code        : "nutanix.prism.image.${cloud.id}.${imageExternalId}",
						internalId  : imageExternalId,
					])
					morpheusContext.virtualImage.location.create([virtualImageLocation], cloud).blockingGet()
					if (letNutanixDownloadImage == false) {
						waitForImageComplete(client, authConfig, imageExternalId, false)
						def uploadResults = NutanixPrismComputeUtility.uploadImage(client, authConfig, imageExternalId, imageFile.inputStream, contentLength)
						if (!uploadResults.success) {
							throw new Exception("Error in uploading the image: ${uploadResults.msg}")
						}
					}
				} else {
					VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
						virtualImage: virtualImage,
						externalId  : imageExternalId,
						imageRegion : cloud.regionCode,
						code        : "nutanix.prism.image.${cloud.id}.${imageExternalId}",
						internalId  : imageExternalId,
					])
					morpheusContext.virtualImage.location.create([virtualImageLocation], cloud).blockingGet()
					throw new Exception("Error in creating the image: ${imageResults.msg}")
				}

				// Wait till the image is COMPLETE
				waitForImageComplete(client, authConfig, imageExternalId)

			}
		} finally {
			morpheusContext.releaseLock(lockKey, [lock:lock]).blockingGet()
		}
		return imageExternalId
	}

	private waitForImageComplete(HttpApiClient apiClient, Map authConfig, String imageExternalId, Boolean requireResources=true) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def imageDetail = NutanixPrismComputeUtility.getImage(apiClient, authConfig, imageExternalId)
				log.debug("imageDetail: ${imageDetail}")
				if(!imageDetail.success && imageDetail.data.code == 404 ) {
					pending = false
				}
				def imageStatus = imageDetail?.data?.status
				def retrievalList = imageStatus?.resources?.retrieval_uri_list
				if(imageDetail.success == true && imageStatus?.state == "COMPLETE" && (!requireResources || retrievalList?.size() > 0)) {
					rtn.success = true
					rtn.data = imageDetail.data
					pending = false
				}
				attempts ++
				if(attempts > 120)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	private getDataDiskList(Workload workload) {
		def volumes = workload.getConfigProperty('volumes')
		def rtn = volumes?.findAll{it.rootVolume == false}?.sort{it.id}
		return rtn
	}

	private buildWorkloadRunConfig(Workload workload, WorkloadRequest workloadRequest, String imageExternalId, Map opts) {

		ComputeServer server = workload.server
		Cloud cloud = server.cloud

		def maxMemory = workload.maxMemory?.div(ComputeUtility.ONE_MEGABYTE) ?: workload.instance.plan.maxMemory.div(ComputeUtility.ONE_MEGABYTE) //MB
		def maxCores = workload.maxCores ?: workload.instance.plan.maxCores ?: 1
		def coresPerSocket = workload.coresPerSocket ?: workload.instance.plan.coresPerSocket ?: 1
		if(coresPerSocket == 0) {
			coresPerSocket = 1
		}
		def maxStorage = this.getRootSize(workload)
		def numSockets = maxCores / coresPerSocket

		def config = new JsonSlurper().parseText(workload.configs)

		def runConfig = [:] + opts + buildRunConfig(server, imageExternalId, workloadRequest.networkConfiguration, config)
		runConfig += [
			workloadId        : workload.id,
			accountId         : workload.account.id,
			maxMemory         : maxMemory,
			maxStorage        : maxStorage,
			cpuCount          : maxCores,
			maxCores          : maxCores,
			coresPerSocket    : coresPerSocket,
			numSockets		  : numSockets,
			networkType       : workload.getConfigProperty('networkType'),
			containerId       : workload.id,
			workloadConfig    : workload.getConfigMap(),
			timezone          : (workload.getConfigProperty('timezone') ?: cloud.timezone),
			proxySettings     : workloadRequest.proxyConfiguration,
			uefi              : workload.getConfigProperty('uefi'),
			secureBoot        : workload.getConfigProperty('secureBoot'),
			noAgent           : (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
			installAgent      : (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true))

		]
		return runConfig
	}

	private buildHostRunConfig(ComputeServer server, HostRequest hostRequest, String imageExternalId, Map opts) {
		
		Cloud cloud = server.cloud
		StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}


		def maxMemory = server.maxMemory?.div(ComputeUtility.ONE_MEGABYTE)
		def maxCores = server.maxCores ?: 1
		def coresPerSocket = server.coresPerSocket  ?: 1
		if(coresPerSocket == 0) {
			coresPerSocket = 1
		}
		def maxStorage = rootVolume.getMaxStorage()
		def numSockets = maxCores / coresPerSocket

		def serverConfig = server.getConfigMap()
//		if(!serverConfig?.clusterName && server.serverGroup) {
//			def serverGroupConfig = server.serverGroup.getConfigMap()
//			serverConfig.clusterName = serverGroupConfig.clusterName
//			serverConfig.categories = serverGroupConfig.categories
//		}


		def runConfig = [:] + opts + buildRunConfig(server, imageExternalId, hostRequest.networkConfiguration, serverConfig)
		runConfig += [
			name              : server.name,
			accountId         : server.account.id,
			maxMemory         : maxMemory,
			maxStorage        : maxStorage,
			cpuCount          : maxCores,
			maxCores          : maxCores,
			coresPerSocket    : coresPerSocket,
			numSockets		  : numSockets,
			timezone          : (server.getConfigProperty('timezone') ?: cloud.timezone),
			proxySettings     : hostRequest.proxyConfiguration,
			noAgent           : (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
			installAgent      : (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true))
		]
		return runConfig
	}


	private buildRunConfig(ComputeServer server, String imageExternalId, NetworkConfiguration networkConfiguration, config ) {
		log.debug "buildRunConfig: ${server} ${config}"

		Cloud cloud = server.cloud
		VirtualImage virtualImage = server.sourceImage
		StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}


		def datastoreId = rootVolume.datastore?.id
		def rootDatastore = morpheusContext.cloud.datastore.listById([datastoreId.toLong()]).firstOrError().blockingGet()
		if(!rootDatastore) {
			log.error("buildRunConfig error: Datastore option is invalid for selected host")
			throw new Exception("There are no available datastores to use based on provisioning options for the target host.")
		}

		if(rootVolume) {
			rootVolume.datastore = rootDatastore
			morpheusContext.storageVolume.save([rootVolume]).blockingGet()
		}

		// Network stuff
		def primaryInterface = networkConfiguration.primaryInterface
		Network network = primaryInterface?.network
		def networkId = network?.externalId
		def networkBackingType = network && network.externalType != 'string' ? network.externalType : 'Network'

		//server.name = stripSpecialCharacters(server.name)

		//set hostname and fqdn
		def hostname = server.getExternalHostname()
		def domainName = server.getExternalDomain()
		def fqdn = hostname
		if (domainName)
			fqdn += '.' + domainName
		//storage type
		def storageType
		if (rootVolume?.type?.code && rootVolume?.type?.code != 'nutanix-prism-standard') {
			storageType = rootVolume.type.externalId //handle thin/thick clone
		} else {
			storageType = cloud.getConfigProperty('diskStorageType')
		}

		def diskList = []
		def datastoreIds = []
		def storageVolumeTypes = [:]
		def serverVolumes = server.volumes?.sort {it.displayOrder}
		serverVolumes?.each { volume ->
			datastoreIds << volume.datastore?.id?.toLong()
			def storageVolumeTypeId = volume.type?.id?.toLong()
			if(!storageVolumeTypes[storageVolumeTypeId]) {
				storageVolumeTypes[storageVolumeTypeId] = morpheusContext.storageVolume.storageVolumeType.get(storageVolumeTypeId).blockingGet()
			}

		}
		datastoreIds = datastoreIds.unique()
		def datastores = morpheusContext.cloud.datastore.listById(datastoreIds).toMap {it.id.toLong()}.blockingGet()

		//categories
		def categories = config.categories?.collect {
			def catString
			if(it instanceof String) {
				catString = it
			} else if( it instanceof Map) {
				catString = it?.value
			}
			if(catString && catString.contains(":")) {
				return catString
			} else {
				return null
			}
		}?.findAll{it != null}

		serverVolumes?.eachWithIndex { volume, index ->
			def storageVolumeType = storageVolumeTypes[volume.type?.id?.toLong()]
			def datastore = datastores[volume.datastore?.id?.toLong()]
			def diskConfig = [
				device_properties: [
					device_type: "DISK",
					disk_address: [
						adapter_type: storageVolumeType.name.toUpperCase(),
						device_index: index
					],
				],
				disk_size_bytes: volume.maxStorage,
				storage_config: [
					storage_container_reference: [
						uuid: datastore.externalId,
						name: datastore.name,
						kind: "storage_container",
					]
				]
			]
			if(virtualImage && volume.rootVolume) {
				diskConfig['data_source_reference'] = [
					uuid: imageExternalId ?: virtualImage.externalId,
					name: virtualImage.name,
					kind: "image"
				]
			}
			diskList << diskConfig
		}
		def nicList = []
		def serverInterfaces = server.interfaces?.sort {it.displayOrder}
		def networkIds = serverInterfaces.collect {
			it.network?.id?.toLong()
		}
		networkIds = networkIds.unique()
		def networks = morpheusContext.network.listById(networkIds).toMap { it.id.toLong()}.blockingGet()
		serverInterfaces?.each { networkInterface ->
			def netId = networkInterface?.network?.id?.toLong()
			def net = netId ? networks[netId] : null
			if(net) {
				def networkConfig = [
						is_connected    : true,
						subnet_reference: [
								uuid: net.externalId,
								name: net.name,
								kind: "subnet"
						]
				]
				if(networkInterface.ipAddress) {
					networkConfig["ip_endpoint_list"] = [
							[
					        "ip": networkInterface.ipAddress
						]
					]
				}
				nicList << networkConfig
			}
		}

		def clusterReference = [
				kind: "cluster",
				uuid: config.clusterName
		]

		def runConfig = [:]
		runConfig += [
				serverId          : server.id,
				cloudId           : cloud.id,
				datastoreId       : datastoreId,
				networkId         : networkId,
				networkBackingType: networkBackingType,
				platform          : server.osType,
				hostname		  : hostname,
				domainName		  : domainName,
				fqdn		      : fqdn,
				storageType		  : storageType,
				diskList	      : diskList,
				nicList			  : nicList,
				skipNetworkWait   : false,
				clusterReference  : clusterReference,
				categories        : categories,

		]
		return runConfig
	}

	private void runVirtualMachine(Cloud cloud, Map runConfig, WorkloadResponse workloadResponse, WorkloadRequest workloadRequest, HostRequest hostRequest) {
		log.debug "runVirtualMachine: ${runConfig}"
		try {

			runConfig.template = runConfig.imageId
			insertVm(cloud, runConfig, workloadResponse, workloadRequest, hostRequest)
			if(workloadResponse.success) {
				finalizeVm(runConfig, workloadResponse)
			}

		} catch(e) {
			log.error("runVirtualMachine error:${e}", e)
			workloadResponse.setError('failed to upload image file')
		}
	}

	def insertVm(Cloud cloud, Map runConfig, WorkloadResponse workloadResponse, WorkloadRequest workloadRequest, HostRequest hostRequest) {
		log.debug "insertVm: ${runConfig}"

		Map authConfig = plugin.getAuthConfig(cloud)
		try {
			//prep for insert

			Process process = workloadRequest?.process ?: hostRequest?.process ?: null
			morpheusContext.process.startProcessStep(process , new ProcessEvent(type: ProcessEvent.ProcessType.provisionConfig), 'configuring')



			ComputeServer server = morpheusContext.computeServer.get(runConfig.serverId).blockingGet()
			Workload sourceWorkload
			VirtualImage virtualImage
			if(runConfig.virtualImageId) {
				try {
					virtualImage = morpheusContext.virtualImage.get(runConfig.virtualImageId).blockingGet()
				} catch(e) {
					log.debug "Error in getting virtualImage ${runConfig.virtualImageId}, ${e}"
				}
			}
			runConfig.serverOs = server.serverOs ?: virtualImage?.osType
			runConfig.osType = (runConfig.serverOs?.platform == PlatformType.windows ? 'windows' : 'linux') ?: virtualImage?.platform
			runConfig.platform = runConfig.osType


			//update server
			server.sshUsername = runConfig.userConfig.sshUsername
			server.sshPassword = runConfig.userConfig.sshPassword
			server.sourceImage = virtualImage
			server.serverOs = runConfig.serverOs
			server.osType = runConfig.osType
			server.osDevice = '/dev/sda'
			server.lvmEnabled = false
			if(runConfig.osType == 'windows') {
				server.guestConsoleType = ComputeServer.GuestConsoleType.rdp
			} else if(runConfig.osType == 'linux') {
				server.guestConsoleType = ComputeServer.GuestConsoleType.ssh
			}


			def newType = findVmNodeServerTypeForCloud(server.cloud.id, server.osType, 'nutanix-prism-provision-provider')
			if(newType && server.computeServerType != newType) {
				server.computeServerType = newType
			}

			server.name = runConfig.name
			server = saveAndGet(server)

			log.debug("create server")

			morpheusContext.process.startProcessStep(process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionDeploy), 'deploying vm')

			Map cloudConfigOpts = workloadRequest?.cloudConfigOpts ?: hostRequest?.cloudConfigOpts ?: null

			// Inform Morpheus to install the agent (or not) after the server is created
			workloadResponse.noAgent = runConfig.noAgent
			workloadResponse.installAgent = runConfig.installAgent

			log.debug "runConfig.installAgent = ${runConfig.installAgent}, runConfig.noAgent: ${runConfig.noAgent}, workloadResponse.installAgent: ${workloadResponse.installAgent}, workloadResponse.noAgent: ${workloadResponse.noAgent}"

			//cloud_init && sysprep
			if(virtualImage?.isCloudInit && server.cloudConfigUser) {
				runConfig.cloudInitUserData = server.cloudConfigUser.encodeAsBase64()
			} else if (virtualImage?.isSysprep && server.cloudConfigUser) {
				runConfig.isSysprep = true
				runConfig.cloudInitUserData = server.cloudConfigUser.encodeAsBase64()
			}

			//main create or clone
			log.debug("create server: ${runConfig}")
			def createResults

			HttpApiClient client = new HttpApiClient()
			Map proxyConfiguration = workloadRequest?.proxyConfiguration ?: hostRequest?.proxyConfiguration ?: null
			client.networkProxy = buildNetworkProxy(proxyConfiguration)

			if(runConfig.snapshotId) {
				createResults = NutanixPrismComputeUtility.cloneSnapshot(client, authConfig, runConfig, runConfig.snapshotId as String)
				log.debug("clone snapshot results: ${createResults}")
			} else if(runConfig.cloneContainerId) {
				sourceWorkload = morpheusContext.workload.get(runConfig.cloneContainerId).blockingGet()
				def sourceServer = sourceWorkload?.server
				def vmUuid = sourceServer?.externalId
				if(server.serverOs?.platform != 'windows') {
					getPlugin().morpheus.executeCommandOnServer(sourceServer, 'sudo rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg; sudo cp /etc/machine-id /tmp/machine-id-old ; sync', false, sourceServer.sshUsername, sourceServer.sshPassword, null, null, null, null, true, true).blockingGet()
				}
				createResults = NutanixPrismComputeUtility.cloneVm(client, authConfig, runConfig, vmUuid)
				log.debug("clone server results: ${createResults}")
			} else if(virtualImage) {
				createResults = NutanixPrismComputeUtility.createVm(client, authConfig, runConfig)
				log.debug("create server results: ${createResults}")
			}

			//check success
			if(createResults.success == true && (createResults.data?.metadata?.uuid || createResults.data?.task_uuid)) {

				server = morpheusContext.computeServer.get(server.id).blockingGet()
				if(virtualImage) {
					virtualImage = morpheusContext.virtualImage.get(virtualImage.id).blockingGet()
				}

				if(createResults.data?.metadata?.uuid) {
					//update server ids
					server.externalId = createResults.data.metadata.uuid
					workloadResponse.externalId = server.externalId
					server.internalId = server.externalId
					server = saveAndGet(server)
				}


				//TODO tagging? No direct mapping
				//applyTags(workload, client)

				morpheusContext.process.startProcessStep(process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionLaunch), 'starting vm')

				def taskId = createResults.data?.status?.execution_context?.task_uuid ?: createResults.data?.task_uuid
				def taskResults = NutanixPrismComputeUtility.checkTaskReady(client, authConfig, taskId)
				if(taskResults.success) {
					if(createResults.data?.task_uuid) {
						def sourceServer = sourceWorkload?.server
						if(sourceServer && server?.serverOs?.platform != 'windows') {
							getPlugin().morpheus.executeCommandOnServer(sourceServer, "sudo bash -c \"echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg\"; sudo cat /tmp/machine-id-old > /etc/machine-id ; sudo rm /tmp/machine-id-old ; sync", true, sourceServer.sshUsername, sourceServer.sshPassword, null, null, null, null, true, true).blockingGet()
						}
						server.externalId = taskResults?.data?.entity_reference_list?.find { it.kind == 'vm'}.uuid
						workloadResponse.externalId = server.externalId
						server.internalId = server.externalId
						server = saveAndGet(server)
					}
					def vmResource = NutanixPrismComputeUtility.waitForPowerState(client, authConfig, server.externalId)
					def startResults = NutanixPrismComputeUtility.startVm(client, authConfig, server.externalId, vmResource.data)
					log.debug("start: ${startResults.success}")
					if (startResults.success) {
						if (startResults.error == true) {
							server.statusMessage = 'Failed to start server'
							server = saveAndGet(server)
						} else {
							//good to go
							def serverDetail = NutanixPrismComputeUtility.checkServerReady(client, authConfig, server.externalId)
							log.debug("serverDetail: ${serverDetail}")
							if (serverDetail.success == true) {

								Map resourcePools = getAllResourcePools(server.cloud)
								ComputeZonePool resourcePool = resourcePools[serverDetail?.virtualMachine?.status?.cluster_reference?.uuid]

								def privateIp = serverDetail.ipAddress
								def publicIp = serverDetail.ipAddress
								server.internalIp = privateIp
								server.externalIp = publicIp
								server.resourcePool = resourcePool

								//update disks
								def disks = serverDetail.diskList

								//some ugly matching
								def volumeCount = 0
								def volumes = server.volumes.sort { it.displayOrder }
								for (int i = 0; i < volumes.size(); i++) {
									def volume = volumes[i]
									def newDisk = disks.find { disk -> disk.device_properties.disk_address.adapter_type == volume.type.name.toUpperCase() && disk.device_properties.disk_address.device_index == volumeCount }
									volume.externalId = newDisk?.uuid

									def deviceName = ''
									if(newDisk.device_properties.disk_address.adapter_type == 'SCSI' || newDisk.device_properties.disk_address.adapter_type == 'SATA') {
										deviceName += 'sd'
									} else {
										deviceName += 'hd'
									}
									def letterIndex = ['a','b','c','d','e','f','g','h','i','j','k','l']
									def indexPos = newDisk.device_properties?.disk_address?.device_index ?: 0
									deviceName += letterIndex[indexPos]
									volume.deviceName = '/dev/' + deviceName
									volume.deviceDisplayName = deviceName
									volumeCount++
								}
								morpheusContext.storageVolume.save(volumes).blockingGet()
								server = saveAndGet(server)
								workloadResponse.success = true
							} else {
								server.statusMessage = 'Failed to load server details'
								server = saveAndGet(server)
							}
						}
					} else {
						server.statusMessage = 'Failed to start server'
						server = saveAndGet(server)
					}
				} else {
					workloadResponse.setError("Failed to create server - task error:  ${taskResults}")
				}

			} else {
				if(createResults.results?.server?.id) {
					server = morpheusContext.computeServer.get(runConfig.serverId).blockingGet()
					server.externalId = createResults.results.server.id
					server.internalId = createResults.results.server.instanceUuid
					server = saveAndGet(server)
				}
				workloadResponse.setError('Failed to create server')
			}

		} catch (e) {
			log.error("runException: ${e}", e)
			workloadResponse.setError('Error running vm')
		}
	}

	def finalizeVm(Map runConfig, WorkloadResponse workloadResponse) {
		log.debug("runTask onComplete: runConfig:${runConfig}, workloadResponse: ${workloadResponse}")
		ComputeServer server = morpheusContext.computeServer.get(runConfig.serverId).blockingGet()
		try {
			if(workloadResponse.success == true) {
				server.statusDate = new Date()
				server.osDevice = '/dev/sda'
				server.dataDevice = '/dev/sda'
				server.lvmEnabled = false
				server.capacityInfo = new ComputeCapacityInfo(maxCores:runConfig.maxCores, maxMemory:runConfig.maxMemory,
						maxStorage:runConfig.maxStorage)
				saveAndGet(server)
			}
		} catch(e) {
			log.error("finalizeVm error: ${e}", e)
			workloadResponse.setError('failed to run server: ' + e)
		}
	}

	def getContainerVolumeSize(Workload workload) {
		def rtn = workload.maxStorage ?: workload.instance.plan?.maxStorage
		if(workload.server?.volumes?.size() > 0) {
			def newMaxStorage = workload.server.volumes.sum{it.maxStorage ?: 0}
			if(newMaxStorage > rtn)
				rtn = newMaxStorage
		}
		return rtn
	}


	private Map getAllResourcePools(Cloud cloud) {
		log.debug "getAllResourcePools: ${cloud}"
		def resourcePoolProjectionIds = morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').map{it.id}.toList().blockingGet()
		def resourcePoolsMap = morpheusContext.cloud.pool.listById(resourcePoolProjectionIds).toMap { it.externalId}.blockingGet()
		resourcePoolsMap
	}

	protected HostResponse workloadResponseToHostResponse(WorkloadResponse workloadResponse) {
		HostResponse hostResponse = new HostResponse([
			unattendCustomized: workloadResponse.unattendCustomized,
			externalId        : workloadResponse.externalId,
			publicIp          : workloadResponse.publicIp,
			privateIp         : workloadResponse.privateIp,
			installAgent      : workloadResponse.installAgent,
			noAgent           : workloadResponse.noAgent,
			createUsers       : workloadResponse.createUsers,
			success           : workloadResponse.success,
			customized        : workloadResponse.customized,
			licenseApplied    : workloadResponse.licenseApplied,
			poolId            : workloadResponse.poolId,
			hostname          : workloadResponse.hostname,
			message           : workloadResponse.message,
			skipNetworkWait   : workloadResponse.skipNetworkWait
		])

		return hostResponse
	}

}
