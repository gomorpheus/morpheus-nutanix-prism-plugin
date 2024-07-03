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
import com.morpheusdata.model.ReferenceData
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
				syncTask.addMatchFunction { MetadataTagIdentityProjection domainObject, Map data ->
					domainObject.externalId == data.display
				}.onDelete { removeItems ->
					removeMissingCategories(removeItems as List<MetadataTag>)
				}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, Map>> updateItems ->
					// Nothing to do
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
				externalId: data.display,
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

	private removeMissingCategories(List<MetadataTag> removeList) {
		log.debug "removeMissingCategories: ${removeList?.size()}"
		morpheusContext.async.metadataTag.remove(removeList).blockingGet()
	}
	
	private getCategoriesAndValues(authConfig) {
		log.debug "getCategoriesAndValues"
		def rtn = [success: true, data: []]
		try {
			ServiceResponse listResult = NutanixPrismComputeUtility.listCategories(apiClient, authConfig)
			if (listResult.success) {
				def categoryNames = listResult.data?.collect { it.name }
				for(categoryName in categoryNames) {
					log.debug "Getting values for ${categoryName}"
					ServiceResponse valueResponse = NutanixPrismComputeUtility.listCategoryValues(apiClient, authConfig, categoryName)
					if(valueResponse.success) {
						valueResponse.data?.each { payload -> 
							rtn.data << [name: categoryName, value: payload.value, display: "${categoryName}:${payload.value}"]
						}
					} else {
						log.warn "Error getting category values for: ${categoryName}"
					}
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
