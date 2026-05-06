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
import com.morpheusdata.model.NetworkResourceGroupMember
import com.morpheusdata.model.NetworkServer
import com.morpheusdata.model.projection.NetworkResourceGroupIdentityProjection
import com.morpheusdata.model.projection.NetworkResourceGroupMemberIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.nutanix.dp1.mic.microseg.v4.config.AddressGroup
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
			if(masterData.success) {
				def category = getAddressGroupCategory(networkServer)
				Observable<NetworkResourceGroupIdentityProjection> domainRecords = morpheusContext.async.networkResourceGroup.list(
					new DataQuery().withFilters(
						new DataFilter('refType', 'NetworkServer'),
						new DataFilter('refId', networkServer.id),
						new DataFilter('category', category)
					)
				)
				SyncTask<NetworkResourceGroupIdentityProjection, AddressGroup, NetworkResourceGroup> syncTask = new SyncTask<>(domainRecords, masterData.data as Collection<Object>) as SyncTask<NetworkResourceGroupIdentityProjection, AddressGroup, NetworkResourceGroup>
				syncTask.addMatchFunction { NetworkResourceGroupIdentityProjection domainObject, AddressGroup apiItem ->
					domainObject.externalId == apiItem.extId
				}.onDelete { removeItems ->
					removeMissingAddressGroups(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<NetworkResourceGroup, AddressGroup>> updateItems ->
					updateMatchedAddressGroups(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingAddressGroups(itemsToAdd)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkResourceGroupIdentityProjection, AddressGroup>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<NetworkResourceGroupIdentityProjection, AddressGroup>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.async.networkResourceGroup.list(
						new DataQuery().withFilter("id", "in", updateItems.collect { it.existingItem.id } as List<Long>)).map { NetworkResourceGroup networkResourceGroup ->
						SyncTask.UpdateItemDto<NetworkResourceGroup, AddressGroup> matchItem = updateItemMap[networkResourceGroup.id] as SyncTask.UpdateItemDto<NetworkResourceGroup, AddressGroup>
						return new SyncTask.UpdateItem<NetworkResourceGroup, AddressGroup>(existingItem:networkResourceGroup, masterItem:matchItem.masterItem)
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute AddressGroupsSync: ${cloud.id}"
	}

	def addMissingAddressGroups(Collection<AddressGroup> addList) {
		log.debug "addMissingAddressGroups ${cloud} ${addList.size()}"
		def category = getAddressGroupCategory(networkServer)
		def account = networkServer.account
		def adds = addList.collect { cloudItem ->
			new NetworkResourceGroup(
				account: account,
				owner: account,
				refType: 'NetworkServer',
				refId: networkServer.id,
				category: category,
				name: cloudItem.name,
				description: cloudItem.description,
				externalId: cloudItem.extId,
				rawData: cloudItem.encodeAsJSON().toString(),
			)
		}

		if(adds) {
			def savedGroups = morpheusContext.services.networkResourceGroup.bulkCreate(adds)
			// sync members for newly created groups
			def groupsByExtId = savedGroups?.collectEntries { [(it.externalId): it] } ?: [:]
			addList.each { cloudItem ->
				def group = groupsByExtId[cloudItem.extId]
				if(group) {
					syncAddressGroupMembers(group, cloudItem)
				}
			}
		}
	}

	private updateMatchedAddressGroups(List updateList) {
		log.debug "updateMatchedAddressGroups: ${cloud} ${updateList.size()}"
		def updates = []

		for(update in updateList) {
			AddressGroup matchItem = update.masterItem
			NetworkResourceGroup existing = update.existingItem
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
			syncAddressGroupMembers(existing, matchItem)
		}
		if(updates) {
			morpheusContext.services.networkResourceGroup.bulkSave(updates)
		}
	}

	private removeMissingAddressGroups(List<NetworkResourceGroupIdentityProjection> removeList) {
		log.debug "removeMissingAddressGroups: ${removeList?.size()}"
		morpheusContext.services.networkResourceGroup.bulkRemove(removeList)
	}

	/**
	 * Syncs member entries (individual IPs and IP ranges) for an AddressGroup into NetworkResourceGroupMember.
	 * IPv4Address members use memberType='IPAddress', memberValue='ip[/prefix]'.
	 * IPv4Range members use memberType='IPRange', memberValue='startIp-endIp'.
	 */
	private syncAddressGroupMembers(NetworkResourceGroup group, AddressGroup cloudItem) {
		log.debug "syncAddressGroupMembers: group=${group.externalId}"
		try {
			def desiredMembers = []
			int order = 0

			cloudItem.ipv4Addresses?.each { addr ->
				def cidr = addr.prefixLength != null ? "${addr.value}/${addr.prefixLength}" : addr.value
				desiredMembers << [type: 'IPAddress', memberType: 'IPAddress', memberValue: cidr, displayOrder: order++]
			}
			cloudItem.ipRanges?.each { range ->
				desiredMembers << [type: 'IPRange', memberType: 'IPRange', memberValue: "${range.startIp}-${range.endIp}", displayOrder: order++]
			}

			def existingMembers = morpheusContext.services.networkResourceGroup.member.list(
				new DataQuery().withFilters(
					new DataFilter('refType', 'NetworkResourceGroup'),
					new DataFilter('refId', group.id)
				)
			)

			def existingByValue = existingMembers.collectEntries { [(it.memberValue): it] }
			def desiredValues = desiredMembers.collect { it.memberValue } as Set

			def toAdd = desiredMembers.findAll { !existingByValue.containsKey(it.memberValue) }.collect { m ->
				new NetworkResourceGroupMember(
					refType: 'NetworkResourceGroup',
					refId: group.id,
					category: group.category + '.member',
					type: m.type,
					memberType: m.memberType,
					memberValue: m.memberValue,
					displayOrder: m.displayOrder,
				)
			}
			def toRemove = existingMembers.findAll { !desiredValues.contains(it.memberValue) }

			if(toAdd) morpheusContext.services.networkResourceGroup.member.bulkCreate(toAdd)
			if(toRemove) morpheusContext.services.networkResourceGroup.member.bulkRemove(toRemove)
		} catch(e) {
			log.error "Error syncing address group members for ${group.externalId}: ${e}", e
		}
	}


}
