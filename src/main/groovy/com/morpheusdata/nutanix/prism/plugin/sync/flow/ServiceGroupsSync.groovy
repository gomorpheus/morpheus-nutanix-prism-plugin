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

	ServiceGroupsSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, NetworkServer networkServer, ApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
		this.networkServer = networkServer
	}

	static String getServiceGroupCategory(NetworkServer networkServer) {
		return "nutanix.prism.flow.service.group.${networkServer.id}"
	}

	def execute() {
		log.debug "BEGIN: execute ServiceGroupsSync: ${cloud.id}"
		try {
			def masterData = NutanixPrismComputeUtility.listServiceGroups(apiClient)
			if (masterData.success) {
				def category = getServiceGroupCategory(networkServer)
				Observable<NetworkResourceGroupIdentityProjection> domainRecords = morpheusContext.async.networkResourceGroup.list(
					new DataQuery().withFilters(
						new DataFilter('refType', 'NetworkServer'),
						new DataFilter('refId', networkServer.id),
						new DataFilter('category', category)
					)
				)
				SyncTask<NetworkResourceGroupIdentityProjection, ServiceGroup, NetworkResourceGroup> syncTask =
					new SyncTask<>(domainRecords, masterData.data as Collection<Object>)
				syncTask.addMatchFunction { NetworkResourceGroupIdentityProjection domainObject, ServiceGroup apiItem ->
					domainObject.externalId == apiItem.extId
				}.onDelete { removeItems ->
					removeMissingServiceGroups(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<NetworkResourceGroup, ServiceGroup>> updateItems ->
					updateMatchedServiceGroups(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingServiceGroups(itemsToAdd)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkResourceGroupIdentityProjection, ServiceGroup>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<NetworkResourceGroupIdentityProjection, ServiceGroup>> updateItemMap =
						updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.networkResourceGroup.list(
						new DataQuery().withFilter("id", "in", updateItems.collect { it.existingItem.id } as List<Long>)
					).map { NetworkResourceGroup networkResourceGroup ->
						SyncTask.UpdateItemDto<NetworkResourceGroup, ServiceGroup> matchItem =
							updateItemMap[networkResourceGroup.id] as SyncTask.UpdateItemDto<NetworkResourceGroup, ServiceGroup>
						return new SyncTask.UpdateItem<NetworkResourceGroup, ServiceGroup>(existingItem: networkResourceGroup, masterItem: matchItem.masterItem)
					}
				}.start()
			}
		} catch (e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute ServiceGroupsSync: ${cloud.id}"
	}

	private addMissingServiceGroups(Collection<ServiceGroup> addList) {
		log.debug "addMissingServiceGroups ${cloud} ${addList.size()}"
		def category = getServiceGroupCategory(networkServer)
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

		if (adds) {
			def savedGroups = morpheusContext.services.networkResourceGroup.bulkCreate(adds)
			def groupsByExtId = savedGroups?.collectEntries { [(it.externalId): it] } ?: [:]
			addList.each { cloudItem ->
				def group = groupsByExtId[cloudItem.extId]
				if (group) {
					syncServiceGroupMembers(group, cloudItem)
				}
			}
		}
	}

	private updateMatchedServiceGroups(List updateList) {
		log.debug "updateMatchedServiceGroups: ${cloud} ${updateList.size()}"
		def updates = []

		for (update in updateList) {
			ServiceGroup matchItem = update.masterItem
			NetworkResourceGroup existing = update.existingItem
			Boolean save = false

			if (existing.name != matchItem.name) {
				existing.name = matchItem.name
				save = true
			}
			if (existing.description != matchItem.description) {
				existing.description = matchItem.description
				save = true
			}
			def payload = matchItem.encodeAsJSON().toString()
			if (existing.rawData != payload) {
				existing.rawData = payload
				save = true
			}
			if (save) {
				updates << existing
			}
			syncServiceGroupMembers(existing, matchItem)
		}
		if (updates) {
			morpheusContext.services.networkResourceGroup.bulkSave(updates)
		}
	}

	private removeMissingServiceGroups(List<NetworkResourceGroupIdentityProjection> removeList) {
		log.debug "removeMissingServiceGroups: ${removeList?.size()}"
		morpheusContext.services.networkResourceGroup.bulkRemove(removeList)
	}

	/**
	 * Syncs port/protocol members for a ServiceGroup into NetworkResourceGroupMember.
	 *
	 * TCP/UDP entries use memberType='TCPPortRange'/'UDPPortRange', memberValue='startPort-endPort'.
	 * ICMP entries use memberType='ICMPService', memberValue='type/code' (or 'all' when isAllAllowed).
	 */
	private syncServiceGroupMembers(NetworkResourceGroup group, ServiceGroup cloudItem) {
		log.debug "syncServiceGroupMembers: group=${group.externalId}"
		try {
			def desiredMembers = []
			int order = 0

			cloudItem.tcpServices?.each { svc ->
				def value = "${svc.startPort}-${svc.endPort}"
				desiredMembers << [type: 'TCPPortRange', memberType: 'TCPPortRange', memberValue: value, displayOrder: order++]
			}
			cloudItem.udpServices?.each { svc ->
				def value = "${svc.startPort}-${svc.endPort}"
				desiredMembers << [type: 'UDPPortRange', memberType: 'UDPPortRange', memberValue: value, displayOrder: order++]
			}
			cloudItem.icmpServices?.each { svc ->
				def value = svc.isAllAllowed ? 'all' : "${svc.type}/${svc.code}"
				desiredMembers << [type: 'ICMPService', memberType: 'ICMPService', memberValue: value, displayOrder: order++]
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

			if (toAdd) morpheusContext.services.networkResourceGroup.member.bulkCreate(toAdd)
			if (toRemove) morpheusContext.services.networkResourceGroup.member.bulkRemove(toRemove)
		} catch (e) {
			log.error "Error syncing service group members for ${group.externalId}: ${e}", e
		}
	}
}

