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
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkServer
import com.morpheusdata.model.SecurityGroup
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.model.projection.SecurityGroupLocationIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.nutanix.dp1.mic.microseg.v4.config.NetworkSecurityPolicy
import com.nutanix.mic.java.client.ApiClient
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class SecurityPoliciesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private ApiClient apiClient
	private NetworkServer networkServer

	SecurityPoliciesSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, NetworkServer networkServer, ApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
		this.networkServer = networkServer
	}

	static String getSecurityPolicyCategory(NetworkServer networkServer) {
		return "nutanix.prism.flow.security.policy.${networkServer.id}"
	}

	def execute() {
		log.debug "BEGIN: execute SecurityPoliciesSync: ${cloud.id}"
		try {
			def masterData = NutanixPrismComputeUtility.listSecurityPolicies(apiClient)
			if (masterData.success) {
				def category = getSecurityPolicyCategory(networkServer)
				Observable<SecurityGroupLocationIdentityProjection> domainRecords = morpheusContext.async.securityGroup.location
					.listSyncProjections('NetworkServer', networkServer.id)
					.filter { it.category == category }
				SyncTask<SecurityGroupLocationIdentityProjection, NetworkSecurityPolicy, SecurityGroupLocation> syncTask =
					new SyncTask<>(domainRecords, masterData.data as Collection<Object>)
				syncTask.addMatchFunction { SecurityGroupLocationIdentityProjection domainObject, NetworkSecurityPolicy apiItem ->
					domainObject.externalId == apiItem.extId
				}.onDelete { removeItems ->
					removeMissingSecurityPolicies(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<SecurityGroupLocation, NetworkSecurityPolicy>> updateItems ->
					updateMatchedSecurityPolicies(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingSecurityPolicies(itemsToAdd)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, NetworkSecurityPolicy>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, NetworkSecurityPolicy>> updateItemMap =
						updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.securityGroup.location.listByIds(updateItems.collect { it.existingItem.id })
						.map { SecurityGroupLocation sgLocation ->
							SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, NetworkSecurityPolicy> matchItem =
								updateItemMap[sgLocation.id] as SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, NetworkSecurityPolicy>
							return new SyncTask.UpdateItem<SecurityGroupLocation, NetworkSecurityPolicy>(existingItem: sgLocation, masterItem: matchItem.masterItem)
						}
				}.start()
			}
		} catch (e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute SecurityPoliciesSync: ${cloud.id}"
	}

	private addMissingSecurityPolicies(Collection<NetworkSecurityPolicy> addList) {
		log.debug "addMissingSecurityPolicies ${cloud} ${addList.size()}"
		def category = getSecurityPolicyCategory(networkServer)
		def account = networkServer.account

		def sgAdds = addList.collect { policy ->
			new SecurityGroup(
				account: account,
				owner: account,
				name: policy.name,
				description: policy.description,
				externalId: policy.extId,
				externalType: 'SecurityPolicy',
				groupType: 'firewall',
				groupLayer: policy.type?.name(),
				category: category,
				rawData: policy.encodeAsJSON().toString(),
			)
		}
		def sgResult = morpheusContext.services.securityGroup.bulkCreate(sgAdds)
		def savedSgByExtId = sgResult.persistedItems?.collectEntries { [(it.externalId): it] } ?: [:]

		def locationAdds = addList.collect { policy ->
			SecurityGroup sg = savedSgByExtId[policy.extId] as SecurityGroup
			new SecurityGroupLocation(
				securityGroup: sg,
				externalId: policy.extId,
				externalType: 'SecurityPolicy',
				networkServer: networkServer,
				refType: 'NetworkServer',
				refId: networkServer.id,
				category: category,
				rawData: policy.encodeAsJSON().toString(),
			)
		}.findAll { it.securityGroup != null }

		if (locationAdds) {
			morpheusContext.services.securityGroup.location.bulkCreate(locationAdds)
		}
	}

	private updateMatchedSecurityPolicies(List<SyncTask.UpdateItem<SecurityGroupLocation, NetworkSecurityPolicy>> updateList) {
		log.debug "updateMatchedSecurityPolicies: ${cloud} ${updateList.size()}"
		def sgUpdates = []
		def locationUpdates = []

		for (update in updateList) {
			NetworkSecurityPolicy matchItem = update.masterItem
			SecurityGroupLocation existing = update.existingItem
			SecurityGroup sg = existing.securityGroup
			boolean sgChanged = false
			boolean locationChanged = false

			if (sg && sg.name != matchItem.name) {
				sg.name = matchItem.name
				sgChanged = true
			}
			if (sg && sg.description != matchItem.description) {
				sg.description = matchItem.description
				sgChanged = true
			}
			def payload = matchItem.encodeAsJSON().toString()
			if (sg && sg.rawData != payload) {
				sg.rawData = payload
				sgChanged = true
			}
			if (sgChanged && sg) {
				sgUpdates << sg
			}

			if (existing.rawData != payload) {
				existing.rawData = payload
				locationChanged = true
			}
			if (locationChanged) {
				locationUpdates << existing
			}
		}

		if (sgUpdates) morpheusContext.services.securityGroup.bulkSave(sgUpdates)
		if (locationUpdates) morpheusContext.services.securityGroup.location.bulkSave(locationUpdates)
	}

	private removeMissingSecurityPolicies(List<SecurityGroupLocationIdentityProjection> removeList) {
		log.debug "removeMissingSecurityPolicies: ${removeList?.size()}"
		def fullLocations = morpheusContext.services.securityGroup.location.listByIds(removeList.collect { it.id })
		morpheusContext.services.securityGroup.location.bulkRemove(fullLocations)
		def sgsToRemove = fullLocations.collect { it.securityGroup }.findAll { it != null }
		if (sgsToRemove) {
			morpheusContext.services.securityGroup.bulkRemove(sgsToRemove)
		}
	}
}

