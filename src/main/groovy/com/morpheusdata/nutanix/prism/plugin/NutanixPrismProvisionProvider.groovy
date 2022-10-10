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
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.HostType
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
			
			println "\u001B[33mAC Log - NutanixPrismProvisionProvider:runWorkload - runConfig - ${runConfig}\u001B[0m"

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
				println "\u001B[33mAC Log - NutanixPrismProvisionProvider:runWorkload 1 - ${opts}\u001B[0m"
				println "\u001B[33mAC Log - NutanixPrismProvisionProvider:runWorkload 1 - ${runConfig}\u001B[0m"
				workloadResponse.createUsers = runConfig.userConfig.createUsers

				runVirtualMachine(cloud, workloadRequest, runConfig, workloadResponse, opts)

			} else {
				server.statusMessage = 'Error on vm image'
			}

			println "\u001B[33mAC Log - NutanixPrismProvisionProvider:runWorkload- ${workloadResponse}\u001B[0m"
			if (workloadResponse.success != true) {
				return new ServiceResponse(success: false, msg: workloadResponse.message ?: 'vm config error', error: workloadResponse.message, data: workloadResponse)
			} else {
				return new ServiceResponse<WorkloadResponse>(success: false, data: workloadResponse)
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
	ServiceResponse stopWorkload(Workload workload) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse startWorkload(Workload workload) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.error()
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
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse<WorkloadResponse> getServerDetails(ComputeServer server) {
		return ServiceResponse.error()
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
		def datastore
		morpheusContext.cloud.datastore.listById([datastoreId.toLong()]).blockingSubscribe { Datastore ds ->
			datastore = ds
		}
		if(!datastore) {
			log.error("buildRunConfig error: Datastore option is invalid for selected host")
			throw new Exception("There are no available datastores to use based on provisioning options for the target host.")
		}

		if(rootVolume) {
			rootVolume.datastore = datastore
			morpheusContext.storageVolume.save([rootVolume]).blockingGet()
		}

		// Network stuff
		def primaryInterface = workloadRequest.networkConfiguration.primaryInterface
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
		StorageVolume rootDisk = this.getRootDisk(workload)
		def storageType
		if (rootDisk?.type?.code && rootDisk?.type?.code != 'vmware-plugin-standard') {
			storageType = rootDisk.type.externalId //handle thin/thick clone
		} else {
			storageType = cloud.getConfigProperty('diskStorageType')
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
				volumes			  : config.volumes,
				networkInterfaces : config.networkInterfaces,
				skipNetworkWait   : false
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

			//main create or clone
			log.debug("create server: ${runConfig}")
			def createResults

			HttpApiClient client = new HttpApiClient()
			client.networkProxy = buildNetworkProxy(workloadRequest.proxyConfiguration)

			if(virtualImage) {
				createResults = NutanixPrismComputeUtility.createVm(client, authConfig, runConfig)
				log.info("create server: ${createResults}")
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

}
