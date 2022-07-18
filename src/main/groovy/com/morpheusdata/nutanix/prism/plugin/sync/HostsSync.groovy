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
			queryResults.serverType = new ComputeServerType(code: 'nutanix-prism-plugin-hypervisor')
			queryResults.serverOs = new OsType(code: 'esxi.6')

			def poolListProjections = []
			morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').filter { poolProjection ->
				return poolProjection.internalId != null && poolProjection.type == 'Cluster'
			}.blockingSubscribe { poolListProjections << it }
			queryResults.clusters = []
			morpheusContext.cloud.pool.listById(poolListProjections.collect { it.id }).blockingSubscribe { queryResults.clusters << it }

			def cloudItems = []
			def listResultSuccess = false
			def listResults = NutanixPrismComputeUtility.listHosts(apiClient, authConfig)
			if (listResults.success) {
				listResultSuccess = true
				cloudItems = listResults?.data?.findAll { cloudItem ->
					cloudItem.status?.resources?.hypervisor != null
				} 
			}

			if (listResultSuccess) {
				// Need to fetch all the disks for all Hosts for sync operations
				def diskResults = NutanixPrismComputeUtility.listDisks(apiClient, authConfig)
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
						domainObject.externalId == cloudItem?.metadata.uuid
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

		def volumeType = new StorageVolumeType(code: 'nutanix-prism-plugin-host-disk')
		def serverType = new ComputeServerType(code: 'nutanix-prism-plugin-hypervisor')
		def serverOs = new OsType(code: 'esxi.6')

		def metricsResult = NutanixPrismComputeUtility.listHostMetrics(apiClient, authConfig, addList?.collect{ it.metadata.uuid } )
		
		for(cloudItem in addList) {
			try {
				def clusterObj = clusters?.find { pool -> pool.externalId == cloudItem.status?.cluster_reference?.uuid }

				def serverConfig = [
						account          : cloud.owner,
						category         : "nutanix.prism.host.${cloud.id}",
						cloud            : cloud,
						name             : cloudItem.status.name,
						resourcePool     : clusterObj,
						externalId       : cloudItem.metadata.uuid,
						uniqueId         : cloudItem.metadata.uuid,
						sshUsername      : 'root',
						status           : 'provisioned',
						provision        : false,
						serverType       : 'hypervisor',
						computeServerType: serverType,
						serverOs         : serverOs,
						osType           : 'esxi',
						hostname         : cloudItem.status.name,
						externalIp       : cloudItem.status.controller_vm?.ip,
						maxCores         : cloudItem.status.resources.num_cpu_cores
				]

				def newServer = new ComputeServer(serverConfig)
				newServer.maxMemory = cloudItem.status.resources.memory_capacity_mib.toLong() * ComputeUtility.ONE_MEGABYTE
				newServer.maxStorage = 0
				newServer.capacityInfo = new ComputeCapacityInfo(maxMemory:newServer.maxMemory)
				if(!morpheusContext.computeServer.create([newServer]).blockingGet()){
					log.error "Error in creating host server ${newServer}"
				}

				def (maxStorage, usedStorage) = syncHostVolumes(newServer, cloudItem, volumeType, cloudHostDisks)
				updateHostStats(newServer, maxStorage, usedStorage, metricsResult)
			} catch(e) {
				log.error "Error in creating host: ${e}", e
			}
		}
	}

	private updateMatchedHosts(Cloud cloud, List clusters, List updateList, List cloudHostDisks) {
		log.debug "updateMatchedHosts: ${cloud} ${updateList.size()}"

		def volumeType = new StorageVolumeType(code: 'nutanix-prism-plugin-host-disk')

		List<ComputeZonePoolIdentityProjection> zoneClusters = []
		def clusterExternalIds = updateList.collect{it.masterItem.status?.cluster_reference?.uuid}.unique()
		morpheusContext.cloud.pool.listSyncProjections(cloud.id, null).filter {
			it.type == 'Cluster' && it.externalId in clusterExternalIds
		}.blockingSubscribe { zoneClusters << it }
		
		def metricsResult = NutanixPrismComputeUtility.listHostMetrics(apiClient, authConfig, updateList?.collect{ it.masterItem.metadata.uuid } )
		
		for(update in updateList) {
			ComputeServer currentServer = update.existingItem
			def matchedServer = update.masterItem
			if(currentServer) {
				def save = false
				def clusterObj = zoneClusters?.find { pool -> pool.externalId == update.masterItem.status?.cluster_reference?.uuid }
				if(currentServer.resourcePool?.id != clusterObj.id) {
					currentServer.resourcePool = new ComputeZonePool(id: clusterObj.id)
					save = true
				}
				if(currentServer.name != matchedServer.status.name) {
					currentServer.name = matchedServer.status.name
					currentServer.hostname = currentServer.name
					save = true
				}

				def memory = matchedServer.status.resources.memory_capacity_mib.toLong() * ComputeUtility.ONE_MEGABYTE
				if(currentServer.maxMemory != memory) {
					currentServer.maxMemory = memory
					save = true
				}

				def externalIp = matchedServer.status.controller_vm?.ip
				if(currentServer.externalIp != externalIp) {
					currentServer.externalIp = externalIp
					save = true
				}

				def maxCores = matchedServer.status.resources.num_cpu_cores
				if(currentServer.maxCores != maxCores) {
					currentServer.maxCores = maxCores
					save = true
				}

				if(save) {
					morpheusContext.computeServer.save([currentServer]).blockingGet()
				}
				def (maxStorage,usedStorage) = syncHostVolumes(currentServer, matchedServer, volumeType, cloudHostDisks)
				updateHostStats(currentServer, maxStorage, usedStorage, metricsResult)
			}
		}
	}

	private removeMissingHosts(Cloud cloud, List removeList) {
		log.debug "removeMissingHosts: ${cloud} ${removeList.size()}"
		morpheusContext.computeServer.remove(removeList).blockingGet()
	}

	private syncHostVolumes(ComputeServer server, host, StorageVolumeType volumeType, List cloudHostDisks) {
		log.debug "syncHostVolumes: ${server?.id} ${host} ${volumeType}"
		
		def totalMaxStorage = 0l
		def totalUsedStorage = 0l
		def matchFunction = { existingItem, masterItem ->
			existingItem.externalId == masterItem.uuid
		}
		def syncLists = NutanixPrismSyncUtils.buildSyncLists(server.volumes, host.status.resources.host_disks_reference_list, matchFunction)

		def addList = []
		if(syncLists.addList) {
			syncLists.addList?.each { cloudDisk ->
				def cloudDiskDetail = cloudHostDisks.find { it.entity_id == cloudDisk.uuid }?.data
				if(cloudDiskDetail) {
					def (maxStorage, usedStorage) = getMaxAndUsedStorage(cloudDiskDetail)
					def newVolume = new StorageVolume(
							[
									type       : volumeType,
									maxStorage : maxStorage,
									usedStorage: usedStorage,
									externalId : cloudDisk.uuid,
									name       : NutanixPrismComputeUtility.getGroupEntityValue(cloudDiskDetail, 'serial')
							]
					)
					addList << newVolume
					totalMaxStorage += maxStorage
					totalUsedStorage += usedStorage
				} else {
					log.info "No disk detail information for ${cloudDisk.uuid} for host ${host.status.name} - adding"
				}
			}
		}
		
		def saveList = []
		syncLists.updateList?.each { updateMap ->
			def cloudDisk = updateMap.masterItem
			def cloudDiskDetail = cloudHostDisks.find { it.entity_id == cloudDisk.uuid }?.data
			if(cloudDiskDetail) {
				StorageVolume existingVolume = updateMap.existingItem
				def save = false

				def (maxStorage, usedStorage) = getMaxAndUsedStorage(cloudDiskDetail)
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

				def name = NutanixPrismComputeUtility.getGroupEntityValue(cloudDiskDetail, 'serial')
				if(existingVolume.name != name) {
					existingVolume.name = name
					save = true
				}
				
				if(save) {
					saveList << existingVolume
				}
			} else {
				log.info "No disk detail information for ${cloudDisk.uuid} for host ${host.status.name} - updating"
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
		def maxStorage = NutanixPrismComputeUtility.getGroupEntityValue(cloudDiskDetail, 'disk_size_bytes')?.toLong()
		def usedStorage = 0
		def percentUsed = NutanixPrismComputeUtility.getGroupEntityValue(cloudDiskDetail, 'storage.usage_ppm')?.toLong()
		if(percentUsed != null) {
			usedStorage = maxStorage * (percentUsed / 1000000)
		}
		return [maxStorage, usedStorage]
	}
	
	private updateHostStats(ComputeServer server, maxStorage, usedStorage, ServiceResponse metricsResult) {
		log.debug "updateHostStats for ${server}"
		try {
			def updates = !server.getComputeCapacityInfo()
			ComputeCapacityInfo capacityInfo = server.getComputeCapacityInfo() ?: new ComputeCapacityInfo()

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
			
			if(capacityInfo.maxMemory != server.maxMemory) {
				capacityInfo?.maxMemory = server.maxMemory
				updates = true
			}

			if(metricsResult.success && metricsResult.data) {
				def metricGroupData = metricsResult.data.find { it.entity_id == server.externalId }?.data
				def memoryUsagePPM = NutanixPrismComputeUtility.getGroupEntityValue(metricGroupData, 'hypervisor_memory_usage_ppm')?.toLong()
				if(memoryUsagePPM) {
					def usedMemory = server.maxMemory * (memoryUsagePPM / 1000000)
					if (capacityInfo.usedMemory != usedMemory || server.usedMemory != usedMemory) {
						capacityInfo.usedMemory = usedMemory
						server.usedMemory = usedMemory
						updates = true
					}
				}

				def cpuUsagePPM = NutanixPrismComputeUtility.getGroupEntityValue(metricGroupData, 'hypervisor_cpu_usage_ppm')?.toLong()
				if(cpuUsagePPM) {
					def cpuUsage = (cpuUsagePPM / 10000)
					if (capacityInfo.maxCpu != cpuUsage || server.usedCpu != cpuUsage) {
						capacityInfo.maxCpu = cpuUsage
						server.usedCpu = cpuUsage
						updates = true
					}
				}
			}

			// How to determine powerstate?!
			if(server.powerState != ComputeServer.PowerState.off) {
				server.powerState = ComputeServer.PowerState.off
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
