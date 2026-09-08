/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.projection.MetadataTagIdentityProjection
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class CategoriesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public CategoriesSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute Categories: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def masterData = getCategoriesAndValues(authConfig)
			if(masterData.success) {
				Observable<MetadataTagIdentityProjection> domainRecords = morpheusContext.async.metadataTag.listIdentityProjections(new DataQuery().withFilters([
					new DataFilter("refType", "ComputeZone"),
					new DataFilter("refId", cloud.id),
				]))
				SyncTask<MetadataTagIdentityProjection, Map, MetadataTag> syncTask = new SyncTask<>(domainRecords, masterData.data)
				// Match on the V4 extId (current format) or the legacy V3 "key:value" composite string
				// still stored on rows synced before this plugin version - the legacy match lets
				// pre-existing rows be picked up as updates (see onUpdate below) rather than deleted
				// and recreated, so their database id - and any existing server tag associations - is
				// preserved while their externalId is migrated to the real V4 extId in place.
				syncTask.addMatchFunction { MetadataTagIdentityProjection domainObject, Map data ->
					domainObject.externalId == data.extId || domainObject.externalId == data.display
				}.onDelete { removeItems ->
					removeMissingCategories(removeItems as List<MetadataTag>)
				}.onUpdate { List<SyncTask.UpdateItem<MetadataTag, Map>> updateItems ->
					migrateLegacyCategories(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingCategories(itemsToAdd as List<Map>)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.async.metadataTag.listById(updateItems?.collect { it.existingItem.id }).map { MetadataTag tag ->
						SyncTask.UpdateItemDto<MetadataTagIdentityProjection, Map> matchItem = updateItemMap[tag.id] as SyncTask.UpdateItemDto<MetadataTagIdentityProjection, Map>
						return new SyncTask.UpdateItem<MetadataTag, Map>(existingItem: tag, masterItem: matchItem.masterItem)
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute Categories: ${cloud.id}"
	}

	def addMissingCategories(List<Map> addList) {
		log.debug "addMissingCategories ${cloud} ${addList.size()}"
		def adds = []

		for(Map data in addList) {

			Map props = [
				refType: 'ComputeZone',
				refId: cloud.id,
				externalId: data.extId,
				name: data.name,
				value: data.value
			]

			def add = new MetadataTag(props)
			adds << add
		}

		if(adds) {
			morpheusContext.async.metadataTag.bulkCreate(adds).blockingGet()
		}
	}

	/**
	 * Rewrites externalId in place for any matched row still carrying the legacy V3 "key:value"
	 * composite string, so it holds the real V4 category extId going forward - this is the one-time
	 * (per row) migration/translation step; every subsequent sync is a no-op since externalId will
	 * already equal extId.
	 */
	private migrateLegacyCategories(List<SyncTask.UpdateItem<MetadataTag, Map>> updateItems) {
		log.debug "migrateLegacyCategories ${cloud} ${updateItems.size()}"
		def saves = []
		updateItems.each { SyncTask.UpdateItem<MetadataTag, Map> updateItem ->
			MetadataTag tag = updateItem.existingItem
			Map data = updateItem.masterItem
			if(tag.externalId != data.extId) {
				tag.externalId = data.extId
				saves << tag
			}
		}
		if(saves) {
			morpheusContext.async.metadataTag.bulkSave(saves).blockingGet()
		}
	}

	private removeMissingCategories(List<MetadataTag> removeList) {
		log.debug "removeMissingCategories: ${removeList?.size()}"
		morpheusContext.async.metadataTag.remove(removeList).blockingGet()
	}
	
	private getCategoriesAndValues(authConfig) {
		log.debug "getCategoriesAndValues"
		def rtn = [success: true, data: []]
		try {
			ServiceResponse listResult = NutanixPrismComputeUtility.listCategoriesV4(apiClient, authConfig)
			if (listResult.success) {
				listResult.data?.each { category ->
					rtn.data << [name: category.key, value: category.value, extId: category.extId, display: "${category.key}:${category.value}"]
				}
			} else {
				rtn.success = false
				log.warn "Error getting list of categories: ${listResult.msg}"
			}
		} catch(e) {
			rtn.success = false
			log.error "Error in getting categories and values: ${e}", e
		}
		rtn
	}
}
