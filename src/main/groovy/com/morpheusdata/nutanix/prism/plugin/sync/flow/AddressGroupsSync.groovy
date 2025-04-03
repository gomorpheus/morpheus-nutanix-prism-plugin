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
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkResourceGroup
import com.morpheusdata.model.NetworkServer
import com.morpheusdata.model.projection.NetworkResourceGroupIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.nutanix.mic.java.client.ApiClient
import io.reactivex.rxjava3.core.Observable
import groovy.util.logging.Slf4j

@Slf4j
class AddressGroupsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private ApiClient apiClient
	private NetworkServer networkServer

	public AddressGroupsSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, NetworkServer networkServer, ApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
		this.networkServer = networkServer
	}

	public static getAddressGroupCategory(NetworkServer networkServer) {
		return "nutanix.prism.flow.address.group.${networkServer.id}"
	}

	def execute() {
		log.debug "BEGIN: execute AddressGroupsSync: ${cloud.id}"
		try {
			def masterData = NutanixPrismComputeUtility.listAddressGroups(apiClient)
			masterData.data = [masterData.data[0]]
			println "\u001B[33mAC Log - AddressGroupsSync:execute- ${masterData.data}\u001B[0m"
			if(false && masterData.success) {
				Observable<NetworkResourceGroupIdentityProjection> domainRecords = morpheusContext.async.networkResourceGroup.list(new DataQuery())
				SyncTask<NetworkResourceGroupIdentityProjection, Map, NetworkResourceGroup> syncTask = new SyncTask<>(domainRecords, masterData.data as Collection<Object>) as SyncTask<NetworkResourceGroupIdentityProjection, Map, NetworkResourceGroup>
				syncTask.addMatchFunction { NetworkResourceGroupIdentityProjection domainObject, Map apiItem ->
					domainObject.externalId == apiItem.metadata.uuid
				}.onDelete { removeItems ->
					removeMissingAddressGroups(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<NetworkResourceGroup, Map>> updateItems ->
					updateMatchedAddressGroups(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingAddressGroups(itemsToAdd)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkResourceGroupIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<NetworkResourceGroupIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>).map {NetworkResourceGroup cloudPool ->
						SyncTask.UpdateItemDto<NetworkResourceGroup, Map> matchItem = updateItemMap[cloudPool.id] as SyncTask.UpdateItemDto<NetworkResourceGroup, Map>
						return new SyncTask.UpdateItem<NetworkResourceGroup,Map>(existingItem:cloudPool, masterItem:matchItem.masterItem)
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute ProjectsSync: ${cloud.id}"
	}

	def addMissingAddressGroups(Collection<Map> addList) {
		log.debug "addMissingProjects ${cloud} ${addList.size()}"
		def adds = []

		for(cloudItem in addList) {
			def addressGroupConfig = [
				accc     : cloud.owner,
				type      : 'AddressGroup',
				name      : cloudItem.data.name,
				externalId: cloudItem.metadata.extId,
				uniqueId  : cloudItem.metadata.uuid,
				internalId: cloudItem.metadata.name,
				refType   : 'ComputeZone',
				refId     : cloud.id,
				cloud     : cloud,
				category  : getAddressGroupCategory(cloud),
				code      : "${getAddressGroupCategory(cloud)}.${cloudItem.metadata.uuid}",
				active    : cloud.defaultPoolSyncActive
			]
			if(cloudItem.metadata.default) {
				poolConfig.defaultPool = true
			}
			def add = new NetworkResourceGroup(addressGroupConfig)
			adds << add
		}

		if(adds) {
			morpheusContext.async.cloud.pool.bulkCreate(adds).blockingGet()
		}
	}

	private updateMatchedAddressGroups(List updateList) {
		log.debug "updateMatchedProjects: ${cloud} ${updateList.size()}"
		def updates = []

		for(update in updateList) {
			def matchItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false

			if(existing.name != matchItem.metadata.name) {
				existing.name = matchItem.metadata.name
				save = true
			}
			if(save) {
				updates << existing
			}
		}
		if(updates) {
			morpheusContext.async.cloud.pool.bulkSave(updates).blockingGet()
		}
	}

	private removeMissingAddressGroups(List<NetworkResourceGroupIdentityProjection> removeList) {
		log.debug "removeMissingProjects: ${removeList?.size()}"
		morpheusContext.services.networkResourceGroup.bulkRemove(removeList)
	}
}
