package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.MetadataTagIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class TagsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public TagsSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute TagsSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def listResults = NutanixPrismComputeUtility.listTags(apiClient, authConfig)
			if (listResults.success) {
				def masterTags = listResults?.data?.entities
				Observable<MetadataTagIdentityProjection> domainRecords = morpheusContext.async.metadataTag.listIdentityProjections(new DataQuery().withFilters([
					new DataFilter("refType", "ComputeZone"),
					new DataFilter("refId", cloud.id),
				]))

				SyncTask<MetadataTagIdentityProjection, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, masterTags)
				syncTask.addMatchFunction { MetadataTagIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.uuid
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<MetadataTagIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<MetadataTagIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.metadataTag.listById(updateItems?.collect { it.existingItem.id }).map { MetadataTag tag ->
						SyncTask.UpdateItemDto<MetadataTagIdentityProjection, Map> matchItem = updateItemMap[tag.id]
						return new SyncTask.UpdateItem<Datastore, Map>(existingItem: tag, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					addMissingTags(itemsToAdd)
				}.onUpdate { List<SyncTask.UpdateItem<MetadataTagIdentityProjection, Map>> updateItems ->
					updateMatchedTags(updateItems)
				}.onDelete { removeItems ->
					removeMissingTags(removeItems)
				}.start()
			}

		} catch(e) {
			log.error "Error in execute of TagsSync: ${e}", e
		}
		log.debug "END: execute TagsSync: ${cloud.id}"
	}

	def addMissingTags(Collection<Map> objList) {
		log.debug "addMissingTags: ${objList?.size()}"
		def tagsToCreate = []
		objList?.each {cloudTag ->
			MetadataTag tag = new MetadataTag(
				refType: 'ComputeZone',
				refId: cloud.id,
				externalId: cloudTag.uuid,
				name: cloudTag.name,
				value: cloudTag.description
			)
			tagsToCreate << tag
		}
		morpheusContext.async.metadataTag.bulkCreate(tagsToCreate).blockingGet()
	}



	private updateMatchedTags(List<SyncTask.UpdateItem<MetadataTagIdentityProjection, Map>> updateList) {
		log.debug "updateMatchedTags: ${cloud} ${updateList.size()}"

		def tagsToSave = []

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem
			def save = false
			
			if(existingItem.name != cloudItem.name) {
				existingItem.name = cloudItem.name
				save = true
			}
			if(existingItem.externalId != cloudItem.uuid) {
				existingItem.externalId = cloudItem.uuid
				save = true
			}
			if(existingItem.value != cloudItem.description) {
				existingItem.value = cloudItem.description
				save = true
			}
			if (existingItem.refType != 'ComputeZone') {
				existingItem.refType = 'ComputeZone'
				existingItem.refId = cloud.id
				save = true
			}

			if(save) {
				tagsToSave << existingItem
			}
		}

		if(tagsToSave) {
			morpheusContext.async.metadataTag.bulkSave(tagsToSave).blockingGet()
		}

	}

	private removeMissingTags(List removeList) {
		log.debug "removeMissingTags: ${removeList?.size()}"
		morpheusContext.async.metadataTag.remove(removeList).blockingGet()
	}


}
