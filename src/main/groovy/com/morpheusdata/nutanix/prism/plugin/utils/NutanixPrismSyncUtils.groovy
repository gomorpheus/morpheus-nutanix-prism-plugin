package com.morpheusdata.nutanix.prism.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.Network
import com.morpheusdata.model.StorageController
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
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

				volume.maxStorage = volume.disk_size_bytes
				rtn.maxStorage += volume.maxStorage
				def save = false
				if(existingVolume.maxStorage != volume.maxStorage) {
					//update it
					existingVolume.maxStorage = volume.maxStorage
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
				morpheusContext.storageVolume.save(saveList).blockingGet()
			}

			// The removes
			if(syncLists.removeList) {
				rtn.changed = true
				if(locationOrServer instanceof ComputeServer) {
					morpheusContext.storageVolume.remove(syncLists.removeList, locationOrServer, false).blockingGet()
				} else {
					morpheusContext.storageVolume.remove(syncLists.removeList, locationOrServer).blockingGet()
				}
			}

			// The adds
			def newVolumes = buildNewStorageVolumes(syncLists.addList, cloud, locationOrServer, null, opts)
			if(newVolumes) {
				rtn.changed = true
				newVolumes?.each { rtn.maxStorage += it.maxStorage }
				morpheusContext.storageVolume.create(newVolumes, locationOrServer).blockingGet()
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
			def volName = (newIndex + index) == 0 ? 'root' : 'data'
			if ((newIndex + index) > 0)
				volName = volName + ' ' + (index + newIndex)
			def volumeConfig = [
					name      : volName,
					size      : volume.maxStorage,
					rootVolume: (newIndex + index) == 0,
					deviceName: volume.device_properties?.disk_address?.adapter_type ? volume.device_properties?.disk_address?.adapter_type : null,
					externalId: volume.uuid,
					internalId: volume.uuid,
					unitNumber: "${volume.device_properties?.disk_address?.device_index}",
					datastore : datastore,
					displayOrder: (index + newIndex),
			]

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
		storageVolume.maxStorage = size?.toLong() ?: volume.maxStorage?.toLong() ?: volume.size?.toLong()
		if(volume.storageType)
			storageVolume.type = new StorageVolumeType(id: volume.storageType?.toLong())
		else
			storageVolume.type = new StorageVolumeType(code: 'nutanix-prism-plugin-disk')
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
					existingInterface.network = new Network(network?.id)
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

				if(save) {
					saveList << existingInterface
				}
			}
			if(saveList?.size() > 0) {
				morpheusContext.computeServer.computeServerInterface.save(saveList).blockingGet()
				rtn = true
			}

			// Remove old ones
			if(syncLists.removeList?.size() > 0) {
				morpheusContext.computeServer.computeServerInterface.remove(syncLists.removeList, server).blockingGet()
				rtn = true
			}

			// Add new ones
			def createList = []
			syncLists.addList?.each { addItem ->
				NetworkIdentityProjection networkProj = networks[addItem.subnet_reference?.uuid]
				def newInterface = new ComputeServerInterface([
						externalId      : addItem.uuid,
						type            : netTypes.find { it.externalId == addItem.nic_type },
						macAddress      : addItem.mac_address,
						name            : addItem.mac_address,
						ipAddress       : addItem.ip_endpoint_list?.getAt(0)?.ip,
						network         : networkProj ? new Network(id: networkProj.id) : null,
						displayOrder    : addItem.displayOrder,
						primaryInterface: addItem.primary
				])
				createList << newInterface
			}
			if(createList?.size() > 0) {
				morpheusContext.computeServer.computeServerInterface.create(createList, server).blockingGet()
				rtn = true
			}
		} catch(e) {
			log.error("syncInterfaces error: ${e}", e)
		}
		return rtn
	}
}
