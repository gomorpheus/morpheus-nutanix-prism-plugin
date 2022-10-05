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
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ProxyConfiguration
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
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
//			Cloud cloud = server.cloud
//			VirtualImage virtualImage = server.sourceImage
//			Map authConfig = plugin.getAuthConfig(cloud)
//
//			client = new HttpApiClient()
//			client.networkProxy = buildNetworkProxy(workloadRequest.proxyConfiguration)
//
//			def imageExternalId
//			def lock
//			def lockKey = "nutanix.prism.imageupload.${cloud.regionCode}.${virtualImage?.id}".toString()
//			try {
//				lock = morpheusContext.acquireLock(lockKey, [timeout: 60l*60l*1000l, ttl: 60l*60l*1000l]) //hold up to a 1 hour lock for image upload
//				if(virtualImage) {
//					VirtualImageLocation virtualImageLocation
//					try {
//						virtualImageLocation = morpheusContext.virtualImage.location.findVirtualImageLocation(virtualImage.id, cloud.id, cloud.regionCode, null, false).blockingGet()
//						if(!virtualImageLocation) {
//							imageExternalId = null
//						}
//					} catch(e) {
//						log.info "Error in findVirtualImageLocation.. could be not found ${e}", e
//					}
//
//					if(imageExternalId) {
//						ServiceResponse response = NutanixPrismComputeUtility.checkImageId(client, authConfig, imageExternalId)
//						if(!response.success) {
//							imageExternalId = null
//						}
//					}
//				}
//
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
//			} finally {
//				morpheusContext.releaseLock(lockKey, [lock:lock])
//			}
//
//			if(imageExternalId) {
//				workloadResponse.installAgent = virtualImage ? virtualImage.installAgent : true
//				opts.installAgent = virtualImage ? virtualImage.installAgent : true
//				//user config
//				def createdBy = getInstanceCreateUser(container.instance)
//				def userGroups = container.instance.userGroups?.toList() ?: []
//				if (container.instance.userGroup && userGroups.contains(container.instance.userGroup) == false) {
//					userGroups << container.instance.userGroup
//				}
//				opts.userConfig = userGroupService.buildContainerUserGroups(opts.account, virtualImage, userGroups,
//						createdBy, opts)
//				opts.server.sshUsername = opts.userConfig.sshUsername
//				opts.server.sshPassword = opts.userConfig.sshPassword
//
//				opts.server.setConfigProperty('publicKeyId', container.getConfigProperty('publicKeyId'))
//				opts.server.serverOs = opts.server.serverOs ?: virtualImage.osType
//				opts.server.osType = opts.server.serverOs?.platform ?: opts.server.osType
//				opts.server.save(flush:true)
//
//
//
//
//				def maxMemory = container.maxMemory ?: container.instance.plan.maxMemory
//				def maxCpu = container.maxCpu ?: container.instance.plan.maxCpu ?: 1
//				def coresPerSocket = container.coresPerSocket ?: container.instance.plan.coresPerSocket ?: 1
//				def dataDisks = getContainerDataDiskList(container)
//				def maxCores = container.maxCores ?: container.instance.plan.maxCores
//				log.debug("datadisks: ${dataDisks}")
//				def createOpts = [account:opts.account, name:opts.server.name, maxMemory:maxMemory, maxStorage:maxStorage,
//				                  coresPerSocket:coresPerSocket, maxCores:maxCores, imageId:imageExternalId, server:opts.server, zone:zoneService.loadFullZone(opts.zone), externalId:opts.server.externalId,
//				                  uuid:opts.server.apiKey, rootVolume:rootVolume, dataDisks:dataDisks, containerId:datastoreId,
//				                  platform:opts.server.serverOs?.platform ?: 'linux', isSysprep:virtualImage?.isSysprep, uefi: virtualImage?.uefi, proxySettings: opts.proxySettings]
//				//network
//				if(createOpts.platform == 'windows') {
//					def lockHost
//					try {
//						lockHost = lockService.acquireLock("container.nutanix.uniqueHostname".toString(), [timeout: 660l * 1000l])
//						opts.server.hostname = ComputeUtility.formatHostname(opts.server.hostname,'windows',opts.server.id)
//						opts.server.save(flush:true)
//						sleep(1000) //add sleep delay
//					} catch(e) {
//						log.error("execContainer error: ${e}", e)
//					} finally {
//						if(lockHost) {
//							lockService.releaseLock("container.nutanix.uniqueHostname".toString(),[lock:lockHost])
//						}
//					}
//				}
//
//				createOpts.hostname = createOpts.server.getExternalHostname()
//				createOpts.domainName = createOpts.server.getExternalDomain()
//				createOpts.fqdn = createOpts.hostname
//				if(createOpts.domainName) {
//					createOpts.fqdn += '.' + createOpts.domainName
//				}
//				createOpts.networkConfig = opts.networkConfig
//				opts.networkConfig = createOpts.networkConfig //needs to be on opts so it gets sent to finalize
//				log.debug("network Config: {}", createOpts.networkConfig)
//				if(opts.networkConfig.success) {
//					createOpts.licenses = licenseService.applyLicense(opts.server.sourceImage, 'ComputeServer', opts.server.id, opts.server.account)?.data?.licenses
//				}
//				//clone
//				if(opts.cloneContainerId) {
//					def cloneContainer = Container.get(opts.cloneContainerId)
//					def snapshot = nutanixSnapshotBackupService.getSnapshotForBackupResult(opts.backupSetId, opts.cloneContainerId)
//					log.info("cloning server from snapshot {}", snapshot.snapshotId)
//					createOpts.snapshotId = snapshot.snapshotId
//				}
//				//cloud init config
//				def cloudFileResults = [success:true]
//				if(virtualImage?.isCloudInit) {
//					log.debug("cloud init detected")
//					def cloudConfigOpts = buildCloudConfigOpts(opts.zone, opts.server, !opts.noAgent,[doPing: false, sendIp:true, timezone: containerConfig?.timezone])
//					cloudConfigOpts.licenses = createOpts.licenses
//					morpheusComputeService.buildCloudNetworkConfig(createOpts.platform, virtualImage, cloudConfigOpts, createOpts.networkConfig)
//					server.cloudConfigUser = morpheusComputeService.buildCloudUserData(createOpts.platform, opts.userConfig, cloudConfigOpts)
//					server.cloudConfigMeta = morpheusComputeService.buildCloudMetaData(createOpts.platform, "morpheus-container-${container.id}", cloudConfigOpts.hostname, cloudConfigOpts)
//					server.cloudConfigNetwork = morpheusComputeService.buildCloudNetworkData(createOpts.platform, cloudConfigOpts)
//					server.save(flush:true)
//					opts.installAgent = opts.installAgent && (cloudConfigOpts.installAgent != true) && !opts.noAgent
//					def insertIso = isCloudInitIso(createOpts)
//					if(insertIso == true) {
//						sleep(5000) //sleep to help ensure db propagation
//						def applianceServerUrl = applianceService.getApplianceUrl(server.zone)
//						def cloudFileUrl = applianceServerUrl + (applianceServerUrl.endsWith('/') ? '' : '/') + 'api/cloud-config/' + opts.server.apiKey
//						def cloudFileDiskName = 'morpheus_' + opts.server.id + '.iso'
//						cloudFileResults = NutanixComputeUtility.insertContainerImage([zone:opts.zone,proxySettings: opts.proxySettings, containerId:datastoreId,
//						                                                               image:[name:cloudFileDiskName, imageUrl:cloudFileUrl, imageType:'iso_image']])
//					} else {
//						//v1 of the api works with cloud config on linux - windows expects sysprep not cloudbase
//						createOpts.cloudConfig = server.cloudConfigUser
//						if(cloudConfigOpts.licenseApplied) {
//							opts.licenseApplied = true
//						}
//						opts.unattendCustomized = cloudConfigOpts.unattendCustomized
//					}
//				} else {
//					opts.createUserList = opts.userConfig.createUsers
//				}
//				if(cloudFileResults.success == true) {
//					createOpts.cloudFileId = cloudFileResults.imageDiskId
//					opts.server.osDevice = '/dev/vda'
//					opts.server.lvmEnabled = false
//					opts.server.managed = true
//					opts.server.osType = (virtualImage?.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage?.platform
//					def newType = findVmNodeZoneType(opts.server.zone.zoneType, opts.server.osType)
//					if(newType && opts.server.computeServerType != newType)
//						opts.server.computeServerType = newType
//					opts.server.save(flush:true)
//					log.debug("create server")
//					def createResults
//					if(createOpts.snapshotId) {
//						//cloning off a snapshot
//						createResults = NutanixComputeUtility.cloneServer(createOpts)
//					} else {
//						//creating off an image
//						createResults = NutanixComputeUtility.createServer(createOpts)
//					}
//					log.info("create server: ${createResults}")
//					if(createResults.success == true && createResults.results?.uuid) {
//						opts.server.refresh()
//						opts.server.externalId = createResults.results.uuid
//						opts.server.powerState = 'on'
//						opts.server.save(flush: true)
//						def vmResults = NutanixComputeUtility.loadVirtualMachine(opts, opts.server.externalId)
//						def startResults = NutanixComputeUtility.startVm(opts + [timestamp:vmResults?.virtualMachine?.logicalTimestamp ?: 1], opts.server.externalId)
//						log.debug("start: ${startResults.success}")
//						if(startResults.success == true) {
//							if(startResults.error == true) {
//								opts.server.statusMessage = 'Failed to start server'
//								//ouch - delet it?
//							} else {
//								//good to go
//								def serverDetail = NutanixComputeUtility.checkServerReady([zone:opts.zone,proxySettings: opts.proxySettings, externalId:opts.server.externalId], opts.server.externalId)
//								log.debug("serverDetail: ${serverDetail}")
//								if(serverDetail.success == true) {
//									def privateIp
//									def publicIp
//									if(opts.networkConfig.primaryInterface && !opts.networkConfig.primaryInterface?.doDhcp) {
//										privateIp = opts.networkConfig.primaryInterface?.ipAddress
//										publicIp = opts.networkConfig.primaryInterface?.ipAddress
//									} else {
//										privateIp = serverDetail.vmDetails?.ipAddresses[0]
//										publicIp = serverDetail.vmDetails?.ipAddresses[0]
//									}
//									if(privateIp)
//										opts.network = applyComputeServerNetworkIp(opts.server, privateIp, publicIp, null, null, 0)
//									def vmDisks = NutanixComputeUtility.getVirtualMachineDisks([zone:opts.zone,proxySettings: opts.proxySettings], opts.server.externalId)?.disks
//									updateVolumes(opts.server, vmDisks)
//									def vmNics = NutanixComputeUtility.getVirtualMachineNics([zone:opts.zone,proxySettings: opts.proxySettings], opts.server.externalId)?.nics
//									updateNics(opts.server, vmNics)
//									opts.server.save()
//									opts.server.capacityInfo = new ComputeCapacityInfo(server: opts.server, maxCores: 1,
//											maxMemory: container.maxMemory ?: container.getConfigProperty('maxMemory').toLong(), maxStorage: container.maxStorage ?: container.getConfigProperty('maxStorage').toLong())
//									opts.server.capacityInfo.save()
//									opts.server.save(flush: true)
//									instanceService.updateInstance(container.instance)
//									rtn.success = true
//								} else {
//									opts.server.statusMessage = 'Failed to load server details'
//								}
//							}
//						} else {
//							opts.server.statusMessage = 'Failed to start server'
//						}
//					} else {
//						opts.server.statusMessage = 'Failed to create server'
//					}
//					if(cloudFileResults.imageId) {
//						NutanixComputeUtility.deleteImage(opts, cloudFileResults.imageId)
//					}
//				} else {
//					log.warn("error on cloud config: ${cloudFileResults}")
//					opts.server.statusMessage = 'Failed to load cloud config'
//				}
//			} else {
//				opts.server.statusMessage = 'Error on vm image'
//			}
//			if (workloadResponse.success != true) {
//				return new ServiceResponse(success: false, msg: workloadResponse.message ?: 'vm config error', error: workloadResponse.message, data: workloadResponse)
//			} else {
//				return new ServiceResponse<WorkloadResponse>(success: true, data: workloadResponse)
//			}
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
}
