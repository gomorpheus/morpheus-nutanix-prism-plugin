package com.morpheusdata.nutanix.prism.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageController
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismSyncUtils {

	static buildSyncLists(existingItems, masterItems, matchExistingToMasterFunc) {
		log.debug "buildSyncLists: ${existingItems}, ${masterItems}"
		def rtn = [addList:[], updateList: [], removeList: []]
		try {
			existingItems?.each { existing ->
				def matches = masterItems?.findAll { matchExistingToMasterFunc(existing, it) }
				if(matches?.size() > 0) {
					matches?.each { match ->
						rtn.updateList << [existingItem:existing, masterItem:match]
					}
				} else {
					rtn.removeList << existing
				}
			}
			masterItems?.each { masterItem ->
				def match = rtn?.updateList?.find {
					it.masterItem == masterItem
				}
				if(!match) {
					rtn.addList << masterItem
				}
			}
		} catch(e) {
			log.error "buildSyncLists error: ${e}", e
		}
		return rtn
	}

	static updateMetrics(ComputeServer server, String memoryUsageName, String cpuUsageName, ServiceResponse metricsResult) {
		log.debug "updateMetrics: ${server}, ${memoryUsageName}, ${cpuUsageName}"

		def updates = false
		if(metricsResult.success && metricsResult.data) {
			def metricGroupData = metricsResult.data.find { it.entity_id == server.externalId }?.data

			if(memoryUsageName) {
				def memoryUsagePPM = NutanixPrismComputeUtility.getGroupEntityValue(metricGroupData, memoryUsageName)?.toLong()
				if (memoryUsagePPM) {
					def usedMemory = server.maxMemory * (memoryUsagePPM / 1000000)
					if (server.capacityInfo.usedMemory != usedMemory || server.usedMemory != usedMemory) {
						server.capacityInfo.usedMemory = usedMemory
						server.usedMemory = usedMemory
						updates = true
					}
				}
			}

			if(cpuUsageName) {
				def cpuUsagePPM = NutanixPrismComputeUtility.getGroupEntityValue(metricGroupData, cpuUsageName)?.toLong()
				if (cpuUsagePPM) {
					def cpuUsage = (cpuUsagePPM / 10000)
					if (server.capacityInfo.maxCpu != cpuUsage || server.usedCpu != cpuUsage) {
						server.capacityInfo.maxCpu = cpuUsage
						server.usedCpu = cpuUsage
						updates = true
					}
				}
			}
		}
		updates
	}

	static syncVolumes(locationOrServer, ArrayList externalVolumes, Cloud cloud, MorpheusContext morpheusContext, opts=[:] ) {
		log.debug "syncVolumes for ${locationOrServer} ${externalVolumes?.size} ${cloud}"
		def rtn = [changed: false, maxStorage: 0l]
		try {
			def serverVolumes = locationOrServer.volumes?.sort{it.displayOrder}

			def matchFunction = { morpheusItem, Map cloudItem ->
				(morpheusItem.externalId && morpheusItem.externalId == "${cloudItem.uuid}".toString())
			}

			def syncLists = buildSyncLists(serverVolumes, externalVolumes, matchFunction)

			def saveList = []
			syncLists.updateList?.each { updateMap ->
				log.debug "Updating ${updateMap}"
				StorageVolume existingVolume = updateMap.existingItem
				def volume = updateMap.masterItem
				volume.maxStorage = volume.disk_size_bytes ?: 0l
				rtn.maxStorage += volume.maxStorage
				def save = false
				if(existingVolume.maxStorage != volume.maxStorage) {
					//update it
					existingVolume.maxStorage = volume.maxStorage
					save = true
				}

				def deviceName = generateVolumeDeviceName(volume, externalVolumes)
				if(existingVolume.deviceName != deviceName) {
					existingVolume.deviceName = deviceName
					save = true
				}
				def rootVolume = deviceName == 'sda'
				if( rootVolume != existingVolume.rootVolume) {
					existingVolume.rootVolume = rootVolume
					save = true
				}

				if(volume.device_properties?.disk_address?.device_index != null && existingVolume.unitNumber != "${volume.device_properties?.disk_address?.device_index}") {
					existingVolume.unitNumber = "${volume.device_properties?.disk_address?.device_index}"
					save = true
				}
				if(existingVolume.datastore?.externalId != volume.storage_config?.storage_container_reference?.uuid) {
					existingVolume.datastore = new DatastoreIdentityProjection(cloud.id, volume.storage_config?.storage_container_reference?.uuid)
					save = true
				}
				if(save) {
					saveList << existingVolume
				}
			}

			if(saveList) {
				rtn.changed = true
				log.debug "Found ${saveList?.size()} volumes to update"
				morpheusContext.async.storageVolume.bulkSave(saveList).blockingGet()
			}

			// The removes
			if(syncLists.removeList) {
				rtn.changed = true
				if(locationOrServer instanceof ComputeServer) {
					morpheusContext.async.storageVolume.remove(syncLists.removeList, locationOrServer, false).blockingGet()
				} else {
					morpheusContext.async.storageVolume.remove(syncLists.removeList, locationOrServer).blockingGet()
				}
			}

			// The adds
			def newVolumes = buildNewStorageVolumes(syncLists.addList, cloud, locationOrServer, null, opts)
			if(newVolumes) {
				rtn.changed = true
				newVolumes?.each { rtn.maxStorage += it.maxStorage }
				morpheusContext.async.storageVolume.create(newVolumes, locationOrServer).blockingGet()
			}
		} catch(e) {
			log.error "Error in syncVolumes: ${e}", e
		}
		rtn
	}

	static buildNewStorageVolumes(volumes, cloud, locationOrServer, account, opts = [:]) {
		log.debug "buildNewStorageVolumes: ${volumes?.size()} ${cloud} ${locationOrServer} ${account} ${opts}"
		def rtn = []
		def existingVolumes = locationOrServer?.volumes
		def newIndex = existingVolumes?.size() ?: 0

		volumes?.eachWithIndex { volume, index ->
			volume.maxStorage = volume.disk_size_bytes
			DatastoreIdentityProjection datastore = volume.storage_config?.storage_container_reference?.uuid ? new DatastoreIdentityProjection(cloud.id, volume.storage_config?.storage_container_reference?.uuid) : null
			def volumeConfig = [
					name        : volume.uuid,
					size        : volume.maxStorage,
					deviceName  : generateVolumeDeviceName(volume, volumes),
					externalId  : volume.uuid,
					internalId  : volume.uuid,
					unitNumber  : "${volume.device_properties?.disk_address?.device_index}",
					datastore   : datastore,
					displayOrder: volume.device_properties.disk_address.device_index,
					storageType : volume.device_properties.disk_address.adapter_type,
			]
			volumeConfig.rootVolume = volumeConfig.deviceName == 'sda'

			def newVolume = buildStorageVolume(account ?: cloud.account, locationOrServer, volumeConfig, (index + newIndex))

			rtn << newVolume
		}
		return rtn
	}


	static StorageVolume buildStorageVolume(Account account, locationOrServer, volume, index, size = null) {
		log.debug "buildStorageVolume: ${account} ${locationOrServer} ${volume} ${index}"
		StorageVolume storageVolume = new StorageVolume()
		storageVolume.name = volume.name
		storageVolume.account = account
		storageVolume.maxStorage = size?.toLong() ?: volume.maxStorage?.toLong() ?: volume.size?.toLong() ?: 0l
		if(volume.storageType) {
			String storageTypeCode = "nutanix-prism-disk-" + volume.storageType.toLowerCase()
			storageVolume.type = new StorageVolumeType(code: storageTypeCode)
		} else
			storageVolume.type = new StorageVolumeType(code: 'nutanix-prism-disk')
		if(volume.externalId)
			storageVolume.externalId = volume.externalId
		if(volume.internalId)
			storageVolume.internalId = volume.internalId
		if(volume.unitNumber)
			storageVolume.unitNumber = volume.unitNumber
		if(volume.datastoreId) {
			storageVolume.datastore = new DatastoreIdentityProjection(id: volume.datastoreId.toLong())
		}
		if(volume.datastore) {
			storageVolume.datastore = volume.datastore
		}
		storageVolume.rootVolume = volume.rootVolume == true
		storageVolume.removable = storageVolume.rootVolume != true
		storageVolume.displayOrder = volume.displayOrder ?: locationOrServer?.volumes?.size() ?: 0
		storageVolume.diskIndex = index
		return storageVolume
	}

	static String generateVolumeDeviceName(diskInfo, List<Map> diskList) {
		def deviceName = ''
		if(!diskInfo.device_properties) {
			if(diskInfo.disk_address.device_bus.toUpperCase() == 'SCSI' || diskInfo.disk_address.device_bus.toUpperCase() == 'SATA') {
				deviceName += 'sd'
			} else {
				deviceName += 'hd'
			}
		} else {
			if(diskInfo.device_properties.disk_address.adapter_type == 'SCSI' || diskInfo.device_properties.disk_address.adapter_type == 'SATA') {
				deviceName += 'sd'
			} else {
				deviceName += 'hd'
			}
		}


		def letterIndex = ['a','b','c','d','e','f','g','h','i','j','k','l']
		def indexPos = diskInfo.disk_address?.device_index ?: diskInfo.device_properties?.disk_address?.device_index ?: 0
		if(diskInfo.disk_address?.device_bus?.toUpperCase() == 'SATA' || diskInfo.device_properties?.disk_address?.adapter_type == 'SATA') {
			indexPos += diskList.count { it.device_properties?.disk_address?.adapter_type == 'SCSI' || it.disk_address?.device_bus?.toUpperCase() == 'SCSI' }
		}
		deviceName += letterIndex[indexPos]

		return deviceName
	}

	static Boolean syncInterfaces(ComputeServer server, List cloudNics, Map networks, List<ComputeServerInterfaceType> netTypes, MorpheusContext morpheusContext) {
		log.debug "syncInterfaces: ${server}"
		def rtn = false
		try {

			def existingInterfaces = server.interfaces
			def masterInterfaces = cloudNics
			if(cloudNics?.size() > 0) {
				cloudNics.eachWithIndex { item, i ->
					item.primary = (i == 0)
					item.displayOrder = i
				}
			}

			def matchFunction = { morpheusItem, Map cloudItem ->
				morpheusItem.externalId == cloudItem.uuid
			}
			def syncLists = buildSyncLists(existingInterfaces, masterInterfaces, matchFunction)

			// Update existing ones
			def saveList = []
			syncLists.updateList?.each { updateItem ->
				def masterInterface = updateItem.masterItem
				ComputeServerInterface existingInterface = updateItem.existingItem

				def save = false
				if(existingInterface.primaryInterface != masterInterface.primary) {
					existingInterface.primaryInterface = masterInterface.primary
					save = true
				}

				def network = networks[masterInterface.subnet_reference?.uuid]
				if(existingInterface.network?.id != network?.id) {
					existingInterface.network = new Network(id: network?.id)
					save = true
				}

				def ipAddress = masterInterface.ip_endpoint_list?.getAt(0)?.ip
				if(existingInterface.ipAddress != ipAddress) {
					existingInterface.ipAddress = ipAddress
					save = true
				}

				def macAddress = masterInterface.mac_address
				if(existingInterface.macAddress != macAddress) {
					existingInterface.macAddress = macAddress
					save = true
				}

				def type = netTypes.find { it.externalId == masterInterface.nic_type }
				if(existingInterface.type?.code != type.code) {
					existingInterface.type = type
					save = true
				}

				if(existingInterface.displayOrder != masterInterface.displayOrder) {
					existingInterface.displayOrder = masterInterface.displayOrder
					save = true
				}

				def nicPosition = cloudNics.indexOf(masterInterface) ?: 0
				def name = "eth${nicPosition}"
				if(existingInterface.name != name) {
					existingInterface.name = name
					save = true
				}

				if(save) {
					saveList << existingInterface
				}
			}
			if(saveList?.size() > 0) {
				morpheusContext.async.computeServer.computeServerInterface.save(saveList).blockingGet()
				rtn = true
			}

			// Remove old ones
			if(syncLists.removeList?.size() > 0) {
				morpheusContext.async.computeServer.computeServerInterface.remove(syncLists.removeList, server).blockingGet()
				rtn = true
			}

			// Add new ones
			def createList = []
			syncLists.addList?.each { addItem ->
				NetworkIdentityProjection networkProj = networks[addItem.subnet_reference?.uuid]
				def nicPosition = cloudNics.indexOf(addItem) ?: 0
				def newInterface = new ComputeServerInterface([
						externalId      : addItem.uuid,
						type            : netTypes.find { it.externalId == addItem.nic_type },
						macAddress      : addItem.mac_address,
						name            : "eth${nicPosition}",
						ipAddress       : addItem.ip_endpoint_list?.getAt(0)?.ip,
						network         : networkProj ? new Network(id: networkProj.id) : null,
						displayOrder    : addItem.displayOrder,
						primaryInterface: addItem.primary
				])
				createList << newInterface
			}
			if(createList?.size() > 0) {
				morpheusContext.async.computeServer.computeServerInterface.create(createList, server).blockingGet()
				rtn = true
			}
		} catch(e) {
			log.error("syncInterfaces error: ${e}", e)
		}
		return rtn
	}

	static updateServerContainersAndInstances(ComputeServer currentServer, ServicePlan plan, MorpheusContext morpheusContext) {
		log.debug "updateServerContainersAndInstances: ${currentServer}"
		try {
			// Save the workloads
			def instanceIds = []
			def workloads = getWorkloadsForServer(currentServer, morpheusContext)
			for(Workload workload in workloads) {
				if(plan) {
					workload.plan = plan
				}
				workload.maxCores = currentServer.maxCores
				workload.maxMemory = currentServer.maxMemory
				workload.coresPerSocket = currentServer.coresPerSocket
				workload.maxStorage = currentServer.maxStorage
				def instanceId = workload.instance?.id
				morpheusContext.async.cloud.saveWorkload(workload).blockingGet()

				if(instanceId) {
					instanceIds << instanceId
				}
			}
			if(instanceIds) {
				def instancesToSave = []
				def instances = morpheusContext.async.instance.listById(instanceIds).toList().blockingGet()
				instances.each { Instance instance ->
					if(plan || instance.plan.code == 'terraform.default') {
						if (instance.containers.every { cnt -> (cnt.plan?.id == currentServer.plan.id && cnt.maxMemory == currentServer.maxMemory && cnt.maxCores == currentServer.maxCores && cnt.coresPerSocket == currentServer.coresPerSocket) || cnt.server.id == currentServer.id }) {
							log.debug("Changing Instance Plan To : ${plan?.name} - memory: ${currentServer.maxMemory} for ${instance.name} - ${instance.id}")
							if(plan) {
								instance.plan = plan
							}
							instance.maxCores = currentServer.maxCores
							instance.maxMemory = currentServer.maxMemory
							instance.maxStorage = currentServer.maxStorage
							instance.coresPerSocket = currentServer.coresPerSocket
							instancesToSave << instance
						}
					}
				}
				if(instancesToSave.size() > 0) {
					morpheusContext.async.instance.bulkSave(instancesToSave).blockingGet()
				}
			}
		} catch(e) {
			log.error "Error in updateServerContainersAndInstances: ${e}", e
		}
	}

	private static getWorkloadsForServer(ComputeServer currentServer, MorpheusContext morpheusContext) {
		def workloads = []
		def projections = morpheusContext.async.cloud.listCloudWorkloadProjections(currentServer.cloud.id).filter { it.serverId == currentServer.id }.toList().blockingGet()
		for(proj in projections) {
			workloads << morpheusContext.async.cloud.getWorkloadById(proj.id).blockingGet()
		}
		workloads
	}
}
