package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class ClustersSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public ClustersSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute ClustersSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def listResults = NutanixPrismComputeUtility.listClusters(apiClient, authConfig)
			if(listResults.success) {
				def masterHosts = listResults?.data?.findAll { cloudItem ->
					cloudItem.status?.resources?.config?.service_list?.contains('AOS')
				} ?: []
				Observable<ComputeZonePoolIdentityProjection> domainRecords = morpheusContext.cloud.pool.listSyncProjections(cloud.id, "nutanix.prism.cluster.${cloud.id}")
				SyncTask<ComputeZonePoolIdentityProjection, Map, ComputeZonePool> syncTask = new SyncTask<>(domainRecords, masterHosts)
				syncTask.addMatchFunction { ComputeZonePoolIdentityProjection domainObject, Map apiItem ->
					domainObject.externalId == apiItem.metadata.uuid
				}.onDelete { removeItems ->
					removeMissingResourcePools(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<ComputeZonePool, Map>> updateItems ->
					updateMatchedResourcePools(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingResourcePools(itemsToAdd)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeZonePoolIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ComputeZonePoolIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>).map {ComputeZonePool computeZonePool ->
						SyncTask.UpdateItemDto<ComputeZonePool, Map> matchItem = updateItemMap[computeZonePool.id]
						return new SyncTask.UpdateItem<ComputeZonePool,Map>(existingItem:computeZonePool, masterItem:matchItem.masterItem)
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute ClustersSync: ${cloud.id}"
	}

	def addMissingResourcePools(List addList) {
		log.debug "addMissingResourcePools ${cloud} ${addList.size()}"
		def adds = []

		for(cloudItem in addList) {
			def clusterData = cloudItem.status
			def poolConfig = [
					owner     : cloud.owner,
					type      : 'Cluster',
					name      : clusterData.name,
					externalId: cloudItem.metadata.uuid,
					uniqueId  : cloudItem.metadata.uuid,
					internalId: clusterData.name,
					refType   : 'ComputeZone',
					refId     : cloud.id,
					cloud     : cloud,
					category  : "nutanix.prism.cluster.${cloud.id}",
					code      : "nutanix.prism.cluster.${cloud.id}.${cloudItem.metadata.uuid}"]

			def add = new ComputeZonePool(poolConfig)
			adds << add
		}

		if(adds) {
			morpheusContext.cloud.pool.create(adds).blockingGet()
		}
	}

	private updateMatchedResourcePools(List updateList) {
		log.debug "updateMatchedResourcePools: ${cloud} ${updateList.size()}"
		def updates = []
		
		for(update in updateList) {
			def matchItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false

			if(existing.name != matchItem.status.name) {
				existing.name = matchItem.status.name
				save = true
			}
			if(save) {
				updates << existing
			}
		}
		if(updates) {
			morpheusContext.cloud.pool.save(updates).blockingGet()
		}
	}

	private removeMissingResourcePools(List<ComputeZonePoolIdentityProjection> removeList) {
		log.debug "removeMissingResourcePools: ${removeList?.size}"
		morpheusContext.cloud.pool.remove(removeList).blockingGet()
	}
}
