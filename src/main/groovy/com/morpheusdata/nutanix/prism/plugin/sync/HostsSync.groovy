package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.OsType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class HostsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient
	private Map authConfig

	public HostsSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute HostsSync: ${cloud.id}"

		try {
			this.authConfig = plugin.getAuthConfig(cloud)
			
			def queryResults = [:]
			queryResults.serverType = new ComputeServerType(code: 'nutanix-prism-hypervisor')
			queryResults.serverOs = new OsType(code: 'esxi.6')

			def poolListProjections = []
			morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').filter { poolProjection ->
				return poolProjection.internalId != null && poolProjection.type == 'Cluster'
			}.blockingSubscribe { poolListProjections << it }
			queryResults.clusters = []
			morpheusContext.cloud.pool.listById(poolListProjections.collect { it.id }).blockingSubscribe { queryResults.clusters << it }

			def cloudItems = []
			def listResultSuccess = false
			def listResults = NutanixPrismComputeUtility.listHostsV2(apiClient, authConfig) // Need this one for the stats
			if (listResults.success) {
				listResultSuccess = true
				cloudItems = listResults?.data
			}

			if (listResultSuccess) {
				// Need to fetch all the disks for all Hosts for sync operations
				def diskResults = NutanixPrismComputeUtility.listDisksV2(apiClient, authConfig)
				if (diskResults.success) {
					def cloudHostDisks = diskResults?.data
					
					def domainRecords = morpheusContext.computeServer.listSyncProjections(cloud.id).filter { ComputeServerIdentityProjection projection ->
						if (projection.category == "nutanix.prism.host.${cloud.id}") {
							return true
						}
						false
					}
					SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, cloudItems)
					syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
						domainObject.externalId == cloudItem?.uuid
					}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
						Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
						morpheusContext.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
							SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
							return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
						}
					}.onAdd { itemsToAdd ->
						addMissingHosts(cloud, queryResults.clusters, itemsToAdd, cloudHostDisks)
					}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
						updateMatchedHosts(cloud, queryResults.clusters, updateItems, cloudHostDisks)
					}.onDelete { removeItems ->
						removeMissingHosts(cloud, removeItems)
					}.start()
				} else {
					log.error "Error in getting disks : ${diskResults}"
				}
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute HostsSync: ${cloud.id}"
	}

	private addMissingHosts(Cloud cloud, List clusters, List addList, List cloudHostDisks) {
		log.debug "addMissingHosts: ${cloud} ${addList.size()}"

		def volumeType = new StorageVolumeType(code: 'nutanix-prism-host-disk')
		def serverType = new ComputeServerType(code: 'nutanix-prism-hypervisor')
		def serverOs = new OsType(code: 'esxi.6')
		
		for(cloudItem in addList) {
			try {
				def clusterObj = clusters?.find { pool -> pool.externalId == cloudItem.cluster_uuid }

				def serverConfig = [
						account          : cloud.owner,
						category         : "nutanix.prism.host.${cloud.id}",
						cloud            : cloud,
						name             : cloudItem.name,
						resourcePool     : clusterObj,
						externalId       : cloudItem.uuid,
						uniqueId         : cloudItem.uuid,
						sshUsername      : 'root',
						status           : 'provisioned',
						provision        : false,
						serverType       : 'hypervisor',
						computeServerType: serverType,
						serverOs         : serverOs,
						osType           : 'esxi',
						hostname         : cloudItem.name,
						externalIp       : cloudItem.hypervisor_address
				]

				def newServer = new ComputeServer(serverConfig)
				if(!morpheusContext.computeServer.create([newServer]).blockingGet()){
					log.error "Error in creating host server ${newServer}"
				}

				def (maxStorage, usedStorage) = syncHostVolumes(newServer, volumeType, cloudHostDisks)
				updateMachineMetrics(
						newServer,
						cloudItem.num_cpu_cores?.toLong(),
						maxStorage?.toLong(),
						usedStorage?.toLong(),
						cloudItem.memory_capacity_in_bytes?.toLong(),
						((cloudItem.memory_capacity_in_bytes ?: 0 ) * (cloudItem.stats.hypervisor_memory_usage_ppm?.toLong() / 1000000.0))?.toLong(),
						(cloudItem.stats.hypervisor_cpu_usage_ppm?.toLong() / 10000.0)
				)
			} catch(e) {
				log.error "Error in creating host: ${e}", e
			}
		}
	}

	private updateMatchedHosts(Cloud cloud, List clusters, List updateList, List cloudHostDisks) {
		log.debug "updateMatchedHosts: ${cloud} ${updateList.size()} ${clusters}"

		def volumeType = new StorageVolumeType(code: 'nutanix-prism-host-disk')

		List<ComputeZonePoolIdentityProjection> zoneClusters = []
		def clusterExternalIds = updateList.collect{ it.masterItem.cluster_uuid }.unique()
		morpheusContext.cloud.pool.listSyncProjections(cloud.id, null).filter {
			it.type == 'Cluster' && it.externalId in clusterExternalIds
		}.blockingSubscribe { zoneClusters << it }

		for(update in updateList) {
			ComputeServer currentServer = update.existingItem
			def matchedServer = update.masterItem
			if(currentServer) {
				def save = false
				def clusterObj = zoneClusters?.find { pool -> pool.externalId == matchedServer.cluster_uuid }
				if(currentServer.resourcePool?.id != clusterObj.id) {
					currentServer.resourcePool = new ComputeZonePool(id: clusterObj.id)
					save = true
				}
				if(currentServer.name != matchedServer.name) {
					currentServer.name = matchedServer.name
					currentServer.hostname = currentServer.name
					save = true
				}

				def externalIp = matchedServer.hypervisor_address
				if(currentServer.externalIp != externalIp) {
					currentServer.externalIp = externalIp
					save = true
				}

				if(save) {
					morpheusContext.computeServer.save([currentServer]).blockingGet()
				}
				def (maxStorage,usedStorage) = syncHostVolumes(currentServer, volumeType, cloudHostDisks)

				updateMachineMetrics(
						currentServer,
						matchedServer.num_cpu_cores?.toLong(),
						maxStorage?.toLong(),
						usedStorage?.toLong(),
						matchedServer.memory_capacity_in_bytes?.toLong(),
						((matchedServer.memory_capacity_in_bytes ?: 0 ) * (matchedServer.stats.hypervisor_memory_usage_ppm?.toLong() / 1000000.0))?.toLong(),
						(matchedServer.stats.hypervisor_cpu_usage_ppm?.toLong() / 10000.0)
				)
			}
		}
	}

	private removeMissingHosts(Cloud cloud, List removeList) {
		log.debug "removeMissingHosts: ${cloud} ${removeList.size()}"
		morpheusContext.computeServer.remove(removeList).blockingGet()
	}

	private syncHostVolumes(ComputeServer server, StorageVolumeType volumeType, List cloudHostDisks) {
		log.debug "syncHostVolumes: ${server?.id} ${volumeType}"
		
		def totalMaxStorage = 0l
		def totalUsedStorage = 0l
		def matchFunction = { existingItem, masterItem ->
			existingItem.externalId == masterItem.uuid
		}
		
		def masterItems = cloudHostDisks.findAll { it.node_uuid == server.externalId }
		def syncLists = NutanixPrismSyncUtils.buildSyncLists(server.volumes, masterItems, matchFunction)

		def addList = []
		if(syncLists.addList) {
			syncLists.addList?.each { cloudDisk ->
				def (maxStorage, usedStorage) = getMaxAndUsedStorage(cloudDisk)
				def newVolume = new StorageVolume(
						[
								type       : volumeType,
								maxStorage : maxStorage,
								usedStorage: usedStorage,
								externalId : cloudDisk.id,
								name       : NutanixPrismComputeUtility.getDiskName(cloudDisk)
						]
				)
				addList << newVolume
				totalMaxStorage += maxStorage
				totalUsedStorage += usedStorage
			}
		}
		
		def saveList = []
		syncLists.updateList?.each { updateMap ->
			def cloudDisk = updateMap.masterItem
			StorageVolume existingVolume = updateMap.existingItem
			def save = false

			def (maxStorage, usedStorage) = getMaxAndUsedStorage(cloudDisk)
			totalMaxStorage += maxStorage
			totalUsedStorage += usedStorage

			if(existingVolume.maxStorage != maxStorage) {
				existingVolume.maxStorage = maxStorage
				save = true
			}

			if(existingVolume.usedStorage != usedStorage) {
				existingVolume.usedStorage = usedStorage
				save = true
			}

			def name = NutanixPrismComputeUtility.getDiskName(cloudDisk)
			if(existingVolume.name != name) {
				existingVolume.name = name
				save = true
			}

			if(save) {
				saveList << existingVolume
			}
		}
		
		if(saveList?.size() > 0) {
			log.debug "Saving ${saveList.size()} storage volumes"
			morpheusContext.storageVolume.save(saveList).blockingGet()
		}
		
		if(syncLists.removeList?.size() > 0) {
			log.debug "Removing ${syncLists.removeList.size()} storage volumes"
			morpheusContext.storageVolume.remove(syncLists.removeList, server, false).blockingGet()
		}

		if(addList?.size() > 0) {
			log.debug "Adding ${addList.size()} storage volumes"
			morpheusContext.storageVolume.create(addList, server).blockingGet()
		}

		return [totalMaxStorage, totalUsedStorage]
	}

	private getMaxAndUsedStorage(cloudDiskDetail) {
		def maxStorage = cloudDiskDetail.disk_size?.toLong()
		def usedStorage = cloudDiskDetail.usage_stats["storage.usage_bytes"]?.toLong()
		return [maxStorage, usedStorage]
	}
	
	private updateMachineMetrics(ComputeServer server, Long maxCores, Long maxStorage, Long usedStorage, Long maxMemory, Long usedMemory, maxCpu) {
		log.debug "updateMachineMetrics for ${server}"
		try {
			def updates = !server.getComputeCapacityInfo()
			ComputeCapacityInfo capacityInfo = server.getComputeCapacityInfo() ?: new ComputeCapacityInfo()

			if(capacityInfo.maxCores != maxCores || server.maxCores != maxCores) {
				capacityInfo.maxCores = maxCores
				server?.maxCores = maxCores
				updates = true
			}

			if(capacityInfo.maxStorage != maxStorage || server.maxStorage != maxStorage) {
				capacityInfo.maxStorage = maxStorage
				server?.maxStorage = maxStorage
				updates = true
			}

			if(capacityInfo.usedStorage != usedStorage || server.usedStorage != usedStorage) {
				capacityInfo.usedStorage = usedStorage
				server?.usedStorage = usedStorage
				updates = true
			}
			
			if(capacityInfo.maxMemory != maxMemory || server.maxMemory != maxMemory) {
				capacityInfo?.maxMemory = maxMemory
				server?.maxMemory = maxMemory
				updates = true
			}

			if(capacityInfo.usedMemory != usedMemory || server.usedMemory != usedMemory) {
				capacityInfo?.usedMemory = usedMemory
				server?.usedMemory = usedMemory
				updates = true
			}

			if(capacityInfo.maxCpu != maxCpu || server.usedCpu != maxCpu) {
				capacityInfo?.maxCpu = maxCpu
				server?.usedCpu = maxCpu
				updates = true
			}

			// How to determine powerstate?!.. right now just using the cpu
			def powerState = capacityInfo.maxCpu > 0 ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
			if(server.powerState != powerState) {
				server.powerState = powerState
				updates = true
			}

			if(updates == true) {
				server.capacityInfo = capacityInfo
				morpheusContext.computeServer.save([server]).blockingGet()
			}
		} catch(e) {
			log.warn("error updating host stats: ${e}", e)
		}
	}
}
