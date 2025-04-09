/*
 * Copyright 2025 Morpheus Data, LLC.
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

package com.morpheusdata.nutanix.prism.plugin.sync.flow

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkResourceGroup
import com.morpheusdata.model.NetworkServer
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.NetworkResourceGroupIdentityProjection
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.nutanix.dp1.mic.microseg.v4.config.ServiceGroup
import com.nutanix.mic.java.client.ApiClient
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ServiceGroupsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private ApiClient apiClient
	private NetworkServer networkServer

	public ServiceGroupsSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, NetworkServer networkServer, ApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
		this.networkServer = networkServer
	}

	public static getServiceGroupCategory(NetworkServer networkServer) {
		return "nutanix.prism.flow.service.group.${networkServer.id}"
	}

	def execute() {
		log.debug "BEGIN: execute ServiceGroupsSync: ${cloud.id}"
		try {
			def masterData = NutanixPrismComputeUtility.listServiceGroups(apiClient)
			if(masterData.success) {
				Observable<ReferenceData> domainRecords = morpheusContext.async.referenceData.list(new DataQuery().withFilters(
					new DataFilter('category', getServiceGroupCategory(networkServer))
				))
				SyncTask<ReferenceDataSyncProjection, ServiceGroup, ReferenceData> syncTask = new SyncTask<>(domainRecords, masterData.data as Collection<ServiceGroup>) as SyncTask<ReferenceDataSyncProjection, ServiceGroup, ReferenceData>
				syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, ServiceGroup apiItem ->
					domainObject.externalId == apiItem.extId
				}.onDelete { removeItems ->
					removeMissingServiceGroups(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, ServiceGroup>> updateItems ->
					updateMatchedServiceGroups(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingServiceGroups(itemsToAdd)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, ServiceGroup>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ReferenceDataSyncProjection, ServiceGroup>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.async.referenceData.list(
						new DataQuery().withFilter("id", "in", updateItems.collect { it.existingItem.id } as List<Long>)).map { ReferenceData referenceData ->
						SyncTask.UpdateItemDto<ReferenceData, ServiceGroup> matchItem = updateItemMap[referenceData.id] as SyncTask.UpdateItemDto<ReferenceData, ServiceGroup>
						return new SyncTask.UpdateItem<ReferenceData,ServiceGroup>(existingItem:referenceData, masterItem:matchItem.masterItem)
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute ServiceGroupsSync: ${cloud.id}"
	}

	def addMissingServiceGroups(Collection<ServiceGroup> addList) {
		log.debug "addMissingServiceGroups ${cloud} ${addList.size()}"
		def adds = []
		def category = getServiceGroupCategory(networkServer)
		for(cloudItem in addList) {
			def serviceGroupConfig = [
				account      : networkServer.account,
				code         : "${category}.${cloudItem.extId}",
				category     : category,
				name         : cloudItem.name,
				keyValue     : cloudItem.extId,
				value        : cloudItem.name,
				rawData      : cloudItem.encodeAsJSON().toString(),
				refType      : 'NetworkServer',
				refId        : "${networkServer.id}",
				type		 : 'ServiceGroup',
				externalId   : cloudItem.extId,
				description  : cloudItem.description,
			]
			def add = new ReferenceData(serviceGroupConfig)
			adds << add
		}

		if(adds) {
			morpheusContext.services.referenceData.bulkCreate(adds)
		}
	}

	private updateMatchedServiceGroups(List updateList) {
		log.debug "updateMatchedServiceGroups: ${cloud} ${updateList.size()}"
		def updates = []

		for(update in updateList) {
			ServiceGroup matchItem = update.masterItem
			ReferenceData existing = update.existingItem
			Boolean save = false

			if(existing.name != matchItem.name) {
				existing.name = matchItem.name
				save = true
			}

			if(existing.description != matchItem.description) {
				existing.description = matchItem.description
				save = true
			}

			def payload = matchItem.encodeAsJSON().toString()
			if(existing.rawData != payload) {
				existing.rawData = payload
				save = true
			}
			if(save) {
				updates << existing
			}
		}
		if(updates) {
			morpheusContext.services.referenceData.bulkSave(updates)
		}
	}

	private removeMissingServiceGroups(List<ReferenceDataSyncProjection> removeList) {
		log.debug "removeMissingServiceGroups: ${removeList?.size()}"
		morpheusContext.services.referenceData.bulkRemove(removeList)
	}
}
