package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismSyncUtils
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class DatastoresSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public DatastoresSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute DatastoresSync: ${cloud.id}"

		try {
			def authConfig = plugin.getAuthConfig(cloud)

			// Fetch our known clusters
			def clusters = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { CloudPoolIdentity projection ->
				return projection.type == 'Cluster' && projection.internalId != null
			}.toList().blockingGet()

			//fetch our known vpcs
			def vpcs = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { CloudPoolIdentity projection ->
				return projection.type == 'VPC' && projection.internalId != null
			}.toList().blockingGet()
			def vpcArray = vpcs.collect {new CloudPool(id: it.id)}

			def listResults = NutanixPrismComputeUtility.listDatastores(apiClient, authConfig)
			if(listResults.success == true) {

				Observable domainRecords = morpheusContext.async.cloud.datastore.listSyncProjections(cloud.id)
				SyncTask<DatastoreIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, listResults?.data)
				syncTask.addMatchFunction { DatastoreIdentity domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.entity_id
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.cloud.datastore.listById(updateItems?.collect { it.existingItem.id }).map { Datastore datastore ->
						SyncTask.UpdateItemDto<DatastoreIdentity, Map> matchItem = updateItemMap[datastore.id]
						return new SyncTask.UpdateItem<Datastore, Map>(existingItem: datastore, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					def adds = []
					itemsToAdd?.each { cloudItem ->
						def online = NutanixPrismComputeUtility.getGroupEntityValue(cloudItem.data, 'state') == 'kComplete'
						def clusterId = NutanixPrismComputeUtility.getGroupEntityValue(cloudItem.data, 'cluster')
						def cluster = clusters.find {
							it.externalId == clusterId
						}
						def datastoreConfig = [
								owner       : new Account(id: cloud.owner.id),
								name        : NutanixPrismComputeUtility.getGroupEntityValue(cloudItem.data, 'container_name'),
								externalId  : cloudItem.entity_id,
								cloud       : cloud,
								storageSize : NutanixPrismComputeUtility.getGroupEntityValue(cloudItem.data, 'storage.user_capacity_bytes')?.toLong(),
								freeSpace   : NutanixPrismComputeUtility.getGroupEntityValue(cloudItem.data, 'storage.user_free_bytes')?.toLong(),
								type        : 'generic',
								category    : "nutanix-prism-datastore.${cloud.id}",
								drsEnabled  : false,
								online      : online,
								refType     : "ComputeZone",
							    refId       : cloud.id
						]
						Datastore add = new Datastore(datastoreConfig)
						add.assignedZonePools = [new CloudPool(id: cluster?.id)]
						//also assign to VPCs
						add.assignedZonePools += vpcArray
						adds << add

					}
					morpheusContext.async.cloud.datastore.bulkCreate(adds).blockingGet()
				}.onUpdate { List<SyncTask.UpdateItem<Datastore, Map>> updateItems ->
					def updatedItems = []
					for(item in updateItems) {
						def masterItem = item.masterItem
						Datastore existingItem = item.existingItem
						def save = false

						def online = NutanixPrismComputeUtility.getGroupEntityValue(masterItem.data, 'state') == 'kComplete'
						if(existingItem.online != online) {
							existingItem.online = online
							save = true
						}

						def name = NutanixPrismComputeUtility.getGroupEntityValue(masterItem.data, 'container_name')
						if(existingItem.name != name) {
							existingItem.name = name
							save = true
						}

						Long freeSpace = NutanixPrismComputeUtility.getGroupEntityValue(masterItem.data, 'storage.user_free_bytes')?.toLong()
						if(existingItem.freeSpace != freeSpace) {
							existingItem.freeSpace = freeSpace
							save = true
						}

						Long storageSize = NutanixPrismComputeUtility.getGroupEntityValue(masterItem.data, 'storage.user_capacity_bytes')?.toLong()
						if(existingItem.storageSize != storageSize) {
							existingItem.storageSize = storageSize
							save = true
						}

						def clusterId = NutanixPrismComputeUtility.getGroupEntityValue(masterItem.data, 'cluster')
						def cluster = clusters.find { it.externalId == clusterId }
						//don't associate zone pool
						if(existingItem.zonePool?.id) {
							existingItem.zonePool = null
							save = true
						}
						//also assign to VPCs
						def zonePools = vpcArray
						if(cluster?.id) {
							zonePools += new CloudPool(id: cluster.id)
						}
						def zonePoolSyncLists = NutanixPrismSyncUtils.buildSyncLists(existingItem.assignedZonePools, zonePools, {e, m ->  {e.id == m.id}})
						if(zonePoolSyncLists.addList.size() > 0) {
							existingItem.assignedZonePools += zonePoolSyncLists.addList
							save = true
						}
						if(save) {
							updatedItems << existingItem
						}
					}
					if(updatedItems.size() > 0 ) {
						morpheusContext.async.cloud.datastore.bulkSave(updatedItems).blockingGet()
					}
				}.onDelete { removeItems ->
					if(removeItems) {
						morpheusContext.async.cloud.datastore.remove(removeItems,null).blockingGet()
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute of DatastoresSync: ${e}", e
		}
		log.debug "END: execute DatastoresSync: ${cloud.id}"
	}
}
