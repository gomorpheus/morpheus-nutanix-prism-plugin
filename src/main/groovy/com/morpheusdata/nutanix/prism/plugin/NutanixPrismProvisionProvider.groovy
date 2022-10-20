package com.morpheusdata.nutanix.prism.plugin

//import com.bertramlabs.plugins.karman.CloudFile
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
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.HostType
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.ProxyConfiguration
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismProvisionProvider extends AbstractProvisionProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext

	NutanixPrismProvisionProvider(NutanixPrismPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		OptionType imageOption = new OptionType([
				name : 'virtual image',
				code : 'nutanix-prism-plugin-provision-image',
				fieldName : 'virtualImageId',
				fieldContext : 'config',
				fieldLabel : 'Image',
				inputType : OptionType.InputType.SELECT,
				displayOrder : 100,
				required : true,
				optionSource : 'nutanixPrismPluginImage'
		])
		[imageOption]
	}

	@Override
	Collection<OptionType> getNodeOptionTypes() {
		return []
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		def servicePlans = []
		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-vm-512', name:'1 vCPU, 512MB Memory', description:'1 vCPU, 512MB Memory', sortOrder:0,
				maxStorage:10l * 1024l * 1024l * 1024l, maxMemory: 1l * 512l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-vm-1024', name:'1 vCPU, 1GB Memory', description:'1 vCPU, 1GB Memory', sortOrder:1,
				maxStorage: 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-vm-2048', name:'1 vCPU, 2GB Memory', description:'1 vCPU, 2GB Memory', sortOrder:2,
				maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-vm-4096', name:'1 vCPU, 4GB Memory', description:'1 vCPU, 4GB Memory', sortOrder:3,
				maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:1, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-vm-8192', name:'2 vCPU, 8GB Memory', description:'2 vCPU, 8GB Memory', sortOrder:4,
				maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCores:2, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-vm-16384', name:'2 vCPU, 16GB Memory', description:'2 vCPU, 16GB Memory', sortOrder:5,
				maxStorage: 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCores:2, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-vm-24576', name:'4 vCPU, 24GB Memory', description:'4 vCPU, 24GB Memory', sortOrder:6,
				maxStorage: 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCores:4, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-vm-32768', name:'4 vCPU, 32GB Memory', description:'4 vCPU, 32GB Memory', sortOrder:7,
				maxStorage: 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCores:4, 
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'nutanix-prism-plugin-internal-custom', editable:false, name:'Nutanix Custom', description:'Nutanix Custom', sortOrder:0,
				customMaxStorage:true, customMaxDataStorage:true, addVolumes:true, customCpu: true, customCores: true, customMaxMemory: true, deletable: false, provisionable: false,
				maxStorage:0l, maxMemory: 0l,  maxCpu:0])
		servicePlans
	}

	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		ComputeServerInterfaceType computeServerInterface = new ComputeServerInterfaceType([
				code:'nutanix-prism-plugin-normal-nic',
				externalId:'NORMAL_NIC',
				name:'Nutanix Prism Plugin Normal NIC',
				defaultType: true,
				enabled: true,
				displayOrder:1
		])
		[computeServerInterface]
	}

	@Override
	Boolean hasDatastores() {
		true
	}

	@Override
	Boolean hasNetworks() {
		true
	}

	@Override
	Boolean hasPlanTagMatch() {
		true
	}

	@Override
	Integer getMaxNetworks() {
		return null
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
			NutanixPrismOptionSourceProvider optionSourceProvider = plugin.getProviderByCode('nutanix-prism-option-source-plugin')
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
		log.debug "validateDockerHost: ${instance} ${opts}"
		return ServiceResponse.success()
	}

	@Override
	public ServiceResponse prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "prepareWorkload: ${workload} ${workloadRequest} ${opts}"

		def rtn = [success: false, msg: null]
		try {
			Long virtualImageId = workload.getConfigProperty('virtualImageId')?.toLong()
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
			def runConfig = buildRunConfig(workload, workloadRequest, opts)

			client = new HttpApiClient()
			client.networkProxy = buildNetworkProxy(workloadRequest.proxyConfiguration)

			def imageExternalId
			def lock
			def lockKey = "nutanix.prism.imageupload.${cloud.regionCode}.${virtualImage?.id}".toString()
			try {
				lock = morpheusContext.acquireLock(lockKey, [timeout: 2l*60l*1000l, ttl: 2l*60l*1000l]).blockingGet() //hold up to a 1 hour lock for image upload
				if(virtualImage) {
					VirtualImageLocation virtualImageLocation
					try {
						virtualImageLocation = morpheusContext.virtualImage.location.findVirtualImageLocation(virtualImage.id, cloud.id, cloud.regionCode, null, false).blockingGet()
						if(!virtualImageLocation) {
							imageExternalId = null
						} else {
							imageExternalId = virtualImage.externalId
						}
					} catch(e) {
						log.info "Error in findVirtualImageLocation.. could be not found ${e}", e
					}
					if(imageExternalId) {
						ServiceResponse response = NutanixPrismComputeUtility.checkImageId(client, authConfig, imageExternalId)
						if(!response.success) {
							imageExternalId = null
						}
					}
				}

//				if(!imageExternalId) { //If its userUploaded and still needs uploaded
//					// Create the image
//					def cloudFiles = morpheusContext.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
//					def imageFile = cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().endsWith(".qcow2")}
//					// The url given will be used by Nutanix to download the image.. it will be in a RUNNING status until the download is complete
//					// For morpheus images, this is fine as it is publicly accessible. But, for customer uploaded images, need to upload the bytes
//					def letNutanixDownloadImage = imageFile?.getURL()?.contains('morpheus-images')
//					def imageResults = NutanixPrismComputeUtility.createImage(client, authConfig,
//							virtualImage.name, 'DISK_IMAGE', letNutanixDownloadImage ? imageFile?.getURL() : null)
//					if(imageResults.success && !letNutanixDownloadImage) {
//						imageExternalId = imageResults.data.metadata.uuid
//						def uploadResults = NutanixPrismComputeUtility.uploadImage(client, authConfig, imageExternalId, imageFile.inputSream)
//						if(!uploadResults.success) {
//							throw new Exception("Error in uploading the image: ${uploadResults.msg}")
//						}
//					} else {
//						throw new Exception("Error in creating the image: ${imageResults.msg}")
//					}
//
//					// Wait till the image is COMPLETE
//					waitForImageComplete(client, authConfig, imageExternalId)
//
//					// Create the VirtualImageLocation
//					VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
//							virtualImage: virtualImage,
//							externalId  : imageExternalId,
//							imageRegion : cloud.regionCode
//					])
//					morpheusContext.virtualImage.location.create([virtualImageLocation], cloud ).blockingGet()
//				}
			} finally {
				morpheusContext.releaseLock(lockKey, [lock:lock]).blockingGet()
			}
			runConfig.imageExternalId = imageExternalId
			runConfig.virtualImageId = server.sourceImage?.id
			runConfig.userConfig = workloadRequest.usersConfiguration
			if(imageExternalId) {

				server.sshUsername = runConfig.userConfig.sshUsername
				server.sshPassword = runConfig.userConfig.sshPassword
				workloadResponse.createUsers = runConfig.userConfig.createUsers

				runVirtualMachine(cloud, workloadRequest, runConfig, workloadResponse, opts)

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
//			if(server.sourceImage?.isCloudInit || (server.sourceImage?.isSysprep && !server.sourceImage?.isForceCustomization)) {
//				def cdRemoveResults = VmwareComputeUtility.ejectVmCdRom(authConfig.apiUrl, authConfig.apiUsername, authConfig.apiPassword,
//						[externalId:server.externalId, fileName:'config.iso'])
//				if(cdRemoveResults.success == true) {
//					def cdDeleteResults = VmwareComputeUtility.removeVmFile(authConfig.apiUrl, authConfig.apiUsername, authConfig.apiPassword,
//							[externalId:server.externalId, datacenter:datacenterId, datastoreId:datastoreId, fileName:'config.iso'])
//				}
//			}
			def vmId = server.externalId
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def serverDetails = NutanixPrismComputeUtility.getVm(client, authConfig, vmId)
			//check if ip changed and update
			def privateIp = serverDetails.ipAddress
			def publicIp = serverDetails.ipAddress
			if(server.internalIp != privateIp) {
				if(server.sshHost == server.privateIp) {
					server.sshHost = privateIp
				}
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
	ServiceResponse stopWorkload(Workload workload) {
		log.debug "stopWorkload: ${workload}"
		if(workload.server?.externalId) {
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			HttpApiClient client = new HttpApiClient()
			client.networkProxy = cloud.apiProxy
			def authConfig = plugin.getAuthConfig(cloud)
			def vmResource = waitForPowerState(client, authConfig, server.externalId)
			def stopResults =NutanixPrismComputeUtility.stopVm(client, authConfig, server.externalId, vmResource.data)
			log.info("stopResults: ${stopResults}")
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
			def vmResource = waitForPowerState(client, authConfig, server.externalId)
			def startResults = NutanixPrismComputeUtility.startVm(client, authConfig, server.externalId, vmResource.data)
			log.info("startResults: ${startResults}")
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
				def vmResource = waitForPowerState(client, authConfig, computeServer.externalId)
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
				def vmResource = waitForPowerState(client, authConfig, computeServer.externalId)
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
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	HostType getHostType() {
		return null
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
		true
	}

	@Override
	Boolean canResizeRootVolume() {
		true
	}

	@Override
	Boolean canCustomizeDataVolumes() {
		true
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
				code: 'nutanix-prism-plugin-disk-scsi',
				externalId: 'SCSI',
				name: 'SCSI'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-plugin-disk-pci',
				externalId: 'PCI',
				name: 'PCI'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-plugin-disk-ide',
				externalId: 'IDE',
				name: 'IDE'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-plugin-disk-sata',
				externalId: 'SATA',
				name: 'SATA'
		])
		volumeTypes
	}

	@Override
	String getCode() {
		return 'nutanix-prism-provision-provider-plugin'
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

	private waitForImageComplete(HttpApiClient apiClient, Map authConfig, String imageExternalId) {
		// TODO
		return true
	}

	private getDataDiskList(Workload workload) {
		def volumes = workload.getConfigProperty('volumes')
		def rtn = volumes?.findAll{it.rootVolume == false}?.sort{it.id}
		return rtn
	}

	private buildRunConfig(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "buildRunConfig: ${workload} ${workloadRequest}"

		ComputeServer server = workload.server
		Cloud cloud = server.cloud
		VirtualImage virtualImage = server.sourceImage

		def maxMemory = workload.maxMemory?.div(ComputeUtility.ONE_MEGABYTE) ?: workload.instance.plan.maxMemory.div(ComputeUtility.ONE_MEGABYTE) //MB
		def maxCores = workload.maxCores ?: workload.instance.plan.maxCores ?: 1
		def coresPerSocket = workload.coresPerSocket ?: workload.instance.plan.coresPerSocket ?: 1
		if(coresPerSocket == 0) {
			coresPerSocket = 1
		}
		def maxStorage = this.getRootSize(workload)
		def numSockets = maxCores / coresPerSocket
		
		def config = new JsonSlurper().parseText(workload.configs)
		// datastore
		StorageVolume rootVolume = workload.server.volumes?.find{it.rootVolume == true}
		def rootVolumeConfig = config?.volumes?.find { 
			it.rootVolume == true
		}
		def datastoreId = rootVolumeConfig.datastoreId
		def rootDatastore
		morpheusContext.cloud.datastore.listById([datastoreId.toLong()]).blockingSubscribe { Datastore ds ->
			rootDatastore = ds
		}
		if(!rootDatastore) {
			log.error("buildRunConfig error: Datastore option is invalid for selected host")
			throw new Exception("There are no available datastores to use based on provisioning options for the target host.")
		}

		if(rootVolume) {
			rootVolume.datastore = rootDatastore
			morpheusContext.storageVolume.save([rootVolume]).blockingGet()
		}

		// Network stuff
		def primaryInterface = workloadRequest.networkConfiguration.primaryInterface
		Network network = primaryInterface?.network
		def networkId = network?.externalId
		def networkBackingType = network && network.externalType != 'string' ? network.externalType : 'Network'
		
		//uefi
		def uefi = false
//		if(virtualImage.uefi) {
//			uefi = virtualImage.uefi
//		}

		//server.name = stripSpecialCharacters(server.name)

		//set hostname and fqdn
		def hostname = server.getExternalHostname()
		def domainName = server.getExternalDomain()
		def fqdn = hostname
		if (domainName)
			fqdn += '.' + domainName
		//storage type
		StorageVolume rootDisk = this.getRootDisk(workload)
		def storageType
		if (rootDisk?.type?.code && rootDisk?.type?.code != 'vmware-plugin-standard') {
			storageType = rootDisk.type.externalId //handle thin/thick clone
		} else {
			storageType = cloud.getConfigProperty('diskStorageType')
		}

		def diskList = []
		def datastoreIds = []
		def storageVolumeTypes = [:]
		config.volumes.each { volume ->
			datastoreIds << volume.datastoreId.toLong()
			def storageVolumeTypeId = volume.storageType.toLong()
			if(!storageVolumeTypes[storageVolumeTypeId]) {
				storageVolumeTypes[storageVolumeTypeId] = morpheusContext.storageVolume.storageVolumeType.get(storageVolumeTypeId).blockingGet()
			}

		}
		datastoreIds = datastoreIds.unique()
		def datastores = [:]
		morpheusContext.cloud.datastore.listById(datastoreIds).blockingSubscribe {
			datastores[it.id.toLong()] = it
		}
		config.volumes?.eachWithIndex { volume, index ->
			def storageVolumeType = storageVolumeTypes[volume.storageType.toLong()]
			def datastore = datastores[volume.datastoreId.toLong()]
			def diskConfig = [
				device_properties: [
					device_type: "DISK",
					disk_address: [
						adapter_type: storageVolumeType.name,
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
					uuid: virtualImage.externalId,
					name: virtualImage.name,
					kind: "image"
				]
			}
			diskList << diskConfig
		}
		println "\u001B[33mAC Log - NutanixPrismProvisionProvider:buildRunConfig- ${workloadRequest.networkConfiguration.primaryInterface}\u001B[0m"
		def nicList = []
		def networkIds = config.networkInterfaces.collect {
			it.network?.id?.toLong()
		}
		networkIds = networkIds.unique()
		def networks = [:]
		morpheusContext.network.listById(networkIds).blockingSubscribe { Network it ->
			networks[it.id.toLong()] = it
		}
		config.networkInterfaces?.each { networkInterface ->
			def net = networks[networkInterface.network.id.toLong()]
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
					networkConfig["ip_endpoint_list"] =  [
					        "ip": networkInterface.ipAddress
					]
				}
				nicList << networkConfig
			}
		}

		def runConfig = [:] + opts
		runConfig += [
				workloadId        : workload.id,
				accountId         : workload.account.id,
				name              : server.name,
				maxMemory         : maxMemory,
				maxStorage        : maxStorage,
				cpuCount          : maxCores,
				maxCores          : maxCores,
				coresPerSocket    : coresPerSocket,
				numSockets		  : numSockets,
				serverId          : server.id,
				cloudId           : cloud.id,
				datastoreId       : datastoreId,
				networkId         : networkId,
				networkBackingType: networkBackingType,
				platform          : server.osType,
				networkType       : workload.getConfigProperty('networkType'),
				containerId       : workload.id,
				workloadConfig    : workload.getConfigMap(),
				timezone          : (workload.getConfigProperty('timezone') ?: cloud.timezone),
				proxySettings     : workloadRequest.proxyConfiguration,
				hostname		  : hostname,
				domainName		  : domainName,
				fqdn		      : fqdn,
				storageType		  : storageType,
				diskList	      : diskList,
				nicList			  : nicList,
				skipNetworkWait   : false,
				uefi              : uefi
		]
		return runConfig
	}

	private void runVirtualMachine(Cloud cloud, WorkloadRequest workloadRequest, Map runConfig, WorkloadResponse workloadResponse, Map opts = [:]) {
		log.debug "runVirtualMachine: ${runConfig}"
		try {

			runConfig.template = runConfig.imageId
			insertVm(cloud, workloadRequest, runConfig, workloadResponse)
			if(workloadResponse.success) {
				finalizeVm(runConfig, workloadResponse)
			}

		} catch(e) {
			log.error("runVirtualMachine error:${e}", e)
			workloadResponse.setError('failed to upload image file')
		}
	}

	def insertVm(Cloud cloud, WorkloadRequest workloadRequest, Map runConfig, WorkloadResponse workloadResponse) {
		log.debug "insertVm: ${runConfig}"

		Map authConfig = plugin.getAuthConfig(cloud)
		try {
			//prep for insert
			morpheusContext.process.startProcessStep(workloadRequest.process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionConfig), 'configuring')

			ComputeServer server = morpheusContext.computeServer.get(runConfig.serverId).blockingGet()
			Workload workload = morpheusContext.cloud.getWorkloadById(runConfig.workloadId).blockingGet()
			PlatformType platformType = PlatformType.valueOf(runConfig.platform)
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
			server.osDevice = '/dev/vda'
			server.lvmEnabled = false


			def newType = findVmNodeServerTypeForCloud(server.cloud.id, server.osType, 'nutanix-prism-provision-provider-plugin')
			if(newType && server.computeServerType != newType) {
				server.computeServerType = newType
			}

			server.name = runConfig.name
			server = saveAndGet(server)

			log.debug("create server")

			morpheusContext.process.startProcessStep(workloadRequest.process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionDeploy), 'deploying vm')

			Map cloudConfigOpts = workloadRequest.cloudConfigOpts

			// Inform Morpheus to not install the agent if we are doing it via cloudInit
			workloadResponse.installAgent = runConfig.installAgent && (cloudConfigOpts.installAgent != true) && !runConfig.noAgent
			workloadResponse.noAgent = runConfig.noAgent

			log.debug "runConfig.installAgent = ${runConfig.installAgent}, runConfig.noAgent: ${runConfig.noAgent}, workloadResponse.installAgent: ${workloadResponse.installAgent}, workloadResponse.noAgent: ${workloadResponse.noAgent}"
			//cloud_init
			if(virtualImage?.isCloudInit && server.cloudConfigUser) {
				log.debug "VirtualImage ${virtualImage} isCloudInit"
				runConfig.cloudInitUserData = server.cloudConfigUser.encodeAsBase64()
			}

			//main create or clone
			log.debug("create server: ${runConfig}")
			def createResults

			HttpApiClient client = new HttpApiClient()
			client.networkProxy = buildNetworkProxy(workloadRequest.proxyConfiguration)

			if(virtualImage) {
				createResults = NutanixPrismComputeUtility.createVm(client, authConfig, runConfig)
				log.info("create server: ${createResults}")
			}

			//check success
			if(createResults.success == true && createResults.data?.metadata?.uuid) {

				server = morpheusContext.computeServer.get(server.id).blockingGet()
				workload = morpheusContext.cloud.getWorkloadById(workload.id).blockingGet()
				if(virtualImage) {
					virtualImage = morpheusContext.virtualImage.get(virtualImage.id).blockingGet()
				}
				//update server ids
				server.externalId = createResults.data.metadata.uuid
				workloadResponse.externalId = server.externalId
				server.internalId = server.externalId
				server = saveAndGet(server)
					
				//TODO tagging
				//applyTags(workload, client)

				morpheusContext.process.startProcessStep(workloadRequest.process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionLaunch), 'starting vm')

				def vmResource = waitForPowerState(client, authConfig, server.externalId)
				def startResults = NutanixPrismComputeUtility.startVm(client, authConfig, server.externalId, vmResource.data)
				log.debug("start: ${startResults.success}")
				if(startResults.success == true) {
					if(startResults.error == true) {
						server.statusMessage = 'Failed to start server'
						server = saveAndGet(server)
					} else {
						//good to go
						def serverDetail = checkServerReady(client, authConfig, server.externalId)
						log.debug("serverDetail: ${serverDetail}")
						if(serverDetail.success == true) {

	//						if(workloadRequest.networkConfiguration.primaryInterface && !workloadRequest.networkConfiguration.primaryInterface?.doDhcp) {
	//							workloadResponse.privateIp = workloadRequest.networkConfiguration.primaryInterface?.ipAddress
	//							workloadResponse.publicIp = workloadRequest.networkConfiguration.primaryInterface?.ipAddress
	//							workloadResponse.poolId = createResults.results.server?.networkPoolId
	//							workloadResponse.hostname = workloadResponse.customized ? runConfig.desiredHostname : createResults.results.server?.hostname
	//						} else {
	//							workloadResponse.privateIp = serverDetail.results?.server?.ipAddress
	//							workloadResponse.publicIp = serverDetail.results?.server?.ipAddress
	//							workloadResponse.poolId = createResults.results.server?.networkPoolId
	//							workloadResponse.hostname = createResults.results.server?.hostname
	//						}

							def privateIp = serverDetail.ipAddress
							def publicIp = serverDetail.ipAddress
							server.internalIp = privateIp
							server.externalIp = publicIp
//							setNetworkInfo(server.interfaces, vmResource.data.spec.resource.nic_list)
//							setVolumeInfo(server.volumes, vmResource.data.spec.resource.disk_list)
//							if(serverDetail.results?.server.ipList) {
//								def interfacesToSave = []
//								serverDetail.results?.server.ipList?.each {ipEntry ->
//									def curInterface = server.interfaces?.find{it.macAddress == ipEntry.macAddress}
//									def saveInterface = false
//									if(curInterface && ipEntry.mode == 'ipv4') {
//										curInterface.ipAddress = ipEntry.ipAddress
//										interfacesToSave << curInterface
//									} else if(curInterface && ipEntry.mode == 'ipv6') {
//										curInterface.ipv6Address = ipEntry.ipAddress
//										interfacesToSave << curInterface
//									}
//								}
//								if(interfacesToSave?.size()) {
//									morpheusContext.computeServer.computeServerInterface.save(interfacesToSave).blockingGet()
//								}
//							}
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
		Workload workload = morpheusContext.cloud.getWorkloadById(runConfig.workloadId).blockingGet()
		try {
			if(workloadResponse.success == true) {
				server.statusDate = new Date()
				server.osDevice = '/dev/vda'
				server.dataDevice = '/dev/vda'
				server.lvmEnabled = false
				server.capacityInfo = new ComputeCapacityInfo(maxCores:runConfig.maxCores, maxMemory:workload.maxMemory,
						maxStorage:getContainerVolumeSize(workload))
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

	def waitForPowerState(HttpApiClient client, Map authConfig, String vmId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def serverDetail = NutanixPrismComputeUtility.getVm(client, authConfig, vmId)
				log.debug("serverDetail: ${serverDetail}")
				if(!serverDetail.success && serverDetail.data.code == 404 ) {
					pending = false
				}
				def serverResource = serverDetail?.data?.spec?.resources
				if(serverDetail.success == true && serverResource.power_state) {
					rtn.success = true
					rtn.data = serverDetail.data
					pending = false
				}
				attempts ++
				if(attempts > 60)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	def checkServerReady(HttpApiClient client, Map authConfig, String vmId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def serverDetail = NutanixPrismComputeUtility.getVm(client, authConfig, vmId)
				log.debug("serverDetail: ${serverDetail}")
				def serverResource = serverDetail?.data?.spec?.resources
				if(serverDetail.success == true && serverResource.power_state == 'ON' && serverResource.nic_list?.size() > 0 && serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{checkIpv4Ip(it)} ) {
					rtn.success = true
					rtn.virtualMachine = serverDetail.data
					rtn.ipAddress = serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{checkIpv4Ip(it)}
					pending = false
				}
				attempts ++
				if(attempts > 60)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}


	static checkIpv4Ip(ipAddress) {
		def rtn = false
		if(ipAddress) {
			if(ipAddress.indexOf('.') > 0 && !ipAddress.startsWith('169'))
				rtn = true
		}
		return rtn
	}

//	def setVolumeInfo(List<StorageVolume> serverVolumes, externalVolumes, Cloud cloud) {
//		log.debug("volumes: ${externalVolumes}")
//		try {
//			def maxCount = externalVolumes?.size()
//
//			// Build up a map of datastores we might be using based on the externalVolumes
//			Map datastoreMap = [:]
//			def datastoreExternalIds = externalVolumes?.collect { it.datastore }?.findAll { it != null}?.unique()
//			morpheusContext.cloud.datastore.listSyncProjections(cloud.id).filter { DatastoreIdentityProjection proj ->
//				proj.externalId in datastoreExternalIds
//			}.blockingSubscribe { DatastoreIdentityProjection proj ->
//				datastoreMap[proj.externalId] = proj
//			}
//			serverVolumes.sort{it.displayOrder}.eachWithIndex { StorageVolume volume, index ->
//				if(index < maxCount) {
//					if(volume.externalId) {
//						//check for changes?
//						log.debug("volume already assigned: ${volume.externalId}")
//					} else {
//						def unitFound = false
//						log.debug("finding volume: ${volume.id} ${volume.controller?.controllerKey ?: '-'}:${volume.unitNumber}")
//						externalVolumes.each { externalVolume ->
//							def externalUnitNumber = externalVolume.unitNumber != null ? "${externalVolume.unitNumber}".toString() : null
//							def externalControllerKey = externalVolume.controllerKey != null ? "${externalVolume.controllerKey}".toString() : null
//							log.debug("external volume: ${externalControllerKey}:${externalUnitNumber} - ")
//							if(volume.controller?.controllerKey && volume.unitNumber && externalUnitNumber &&
//									externalUnitNumber == volume.unitNumber && volume.controller.controllerKey == externalControllerKey) {
//								log.debug("found matching disk: ${externalControllerKey}:${externalUnitNumber}")
//								unitFound = true
//								if(volume.controllerKey == null && externalVolume.controllerKey != null)
//									volume.controllerKey = "${externalVolume.controllerKey}".toString()
//								volume.externalId = externalVolume.key
//								volume.internalId = externalVolume.fileName
//								if(externalVolume.datastore) {
//									volume.datastore = datastoreMap[externalVolume.datastore]
//								}
//								morpheusContext.storageVolume.save([volume]).blockingGet()
//							}
//						}
//						if(unitFound != true) {
//							externalVolumes.each { externalVolume ->
//								def externalUnitNumber = externalVolume.unitNumber != null ? "${externalVolume.unitNumber}".toString() : null
//								if(volume.unitNumber && externalUnitNumber && externalUnitNumber == volume.unitNumber) {
//									log.debug("found matching disk: ${externalUnitNumber}")
//									unitFound = true
//									if(volume.controllerKey == null && externalVolume.controllerKey != null)
//										volume.controllerKey = "${externalVolume.controllerKey}".toString()
//									volume.externalId = externalVolume.key
//									volume.internalId = externalVolume.fileName
//									if(externalVolume.datastore) {
//										volume.datastore = datastoreMap[externalVolume.datastore]
//									}
//									morpheusContext.storageVolume.save([volume]).blockingGet()
//								}
//							}
//						}
//						if(unitFound != true) {
//							def sizeRange = [min:(volume.maxStorage - ComputeUtility.ONE_GIGABYTE), max:(volume.maxStorage + ComputeUtility.ONE_GIGABYTE)]
//							externalVolumes.each { externalVolume ->
//								def sizeCheck = externalVolume.size * ComputeUtility.ONE_KILOBYTE
//								def externalKey = externalVolume.key != null ? "${externalVolume.key}".toString() : null
//								log.debug("volume size check - ${externalKey}: ${sizeCheck} between ${sizeRange.min} and ${sizeRange.max}")
//								if(unitFound != true && sizeCheck > sizeRange.min && sizeCheck < sizeRange.max) {
//									def dupeCheck = serverVolumes.find{it.externalId == externalKey}
//									if(!dupeCheck) {
//										//assign a match to the volume
//										unitFound = true
//										if(volume.controllerKey == null && externalVolume.controllerKey != null) {
//											volume.controllerKey = "${externalVolume.controllerKey}".toString()
//										}
//										if(externalVolume.datastore) {
//											volume.datastore = datastoreMap[externalVolume.datastore]
//										}
//										volume.externalId = externalVolume.key
//										volume.internalId = externalVolume.fileName
//										morpheusContext.storageVolume.save([volume]).blockingGet()
//									} else {
//										log.debug("found dupe volume")
//									}
//								}
//							}
//						}
//					}
//				}
//			}
//		} catch(e) {
//			log.error("setVolumeInfo error: ${e}", e)
//		}
//	}
//
//	def setNetworkInfo(List<ComputeServerInterface> serverInterfaces, externalNetworks, newInterface = null, NetworkConfiguration networkConfig=null) {
//		log.info("networks: ${externalNetworks}")
//		try {
//			if(externalNetworks?.size() > 0) {
//				serverInterfaces?.eachWithIndex { ComputeServerInterface networkInterface, index ->
//					if(networkInterface.externalId) {
//						//check for changes?
//					} else {
//						def matchNetwork = externalNetworks.find{networkInterface.type?.code == it.type && networkInterface.externalId == "${it.key}"}
//						if(!matchNetwork)
//							matchNetwork = externalNetworks.find{(networkInterface.type?.code == it.type || networkInterface.type == null) && it.row == networkInterface.displayOrder}
//						if(matchNetwork) {
//							networkInterface.externalId = "${matchNetwork.key}"
//							networkInterface.internalId = "${matchNetwork.unitNumber}"
//							if(matchNetwork.macAddress && matchNetwork.macAddress != networkInterface.macAddress) {
//								log.debug("setting mac address: ${matchNetwork.macAddress}")
//								networkInterface.macAddress = matchNetwork.macAddress
//							}
//							if(networkInterface.type == null) {
//								networkInterface.type = new ComputeServerInterfaceType(code: matchNetwork.type)
//							}
//							if(matchNetwork.macAddress && networkConfig) {
//								if(networkConfig.primaryInterface.id == networkInterface.id) {
//									networkConfig.primaryInterface.macAddress = matchNetwork.macAddress
//								} else {
//									def matchedNetwork = networkConfig.extraInterfaces?.find{it.id == networkInterface.id}
//									if(matchedNetwork) {
//										matchedNetwork.macAddress = matchNetwork.macAddress
//									}
//								}
//							}
//							//networkInterface.name = matchNetwork.name
//							//networkInterface.description = matchNetwork.description
//							morpheusContext.computeServer.computeServerInterface.save([networkInterface]).blockingGet()
//						}
//					}
//				}
//			}
//		} catch(e) {
//			log.error("setNetworkInfo error: ${e}", e)
//		}
//	}
}
