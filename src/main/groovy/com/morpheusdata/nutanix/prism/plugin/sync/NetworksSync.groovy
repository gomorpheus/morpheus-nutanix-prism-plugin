package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.Network
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class NetworksSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public NetworksSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute NetworksSync: ${cloud.id}"
		try {
			def networkTypes = plugin.cloudProvider.getNetworkTypes()

			def clusters = []
			morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').filter { ComputeZonePoolIdentityProjection projection ->
				return projection.type == 'Cluster' && projection.internalId != null
			}.blockingSubscribe { clusters << it }

			def authConfig = plugin.getAuthConfig(cloud)
			def listResults = NutanixPrismComputeUtility.listNetworks(apiClient, authConfig)
			if (listResults.success) {

				def domainRecords = morpheusContext.cloud.network.listSyncProjections(cloud.id)
				SyncTask<NetworkIdentityProjection, Map, ComputeZonePool> syncTask = new SyncTask<>(domainRecords, listResults.data)
				syncTask.addMatchFunction { NetworkIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.metadata.uuid
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.cloud.network.listById(updateItems?.collect { it.existingItem.id }).map { Network network ->
						SyncTask.UpdateItemDto<NetworkIdentityProjection, Map> matchItem = updateItemMap[network.id]
						return new SyncTask.UpdateItem<NetworkIdentityProjection, Map>(existingItem: network, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					def adds = []
					itemsToAdd?.each { cloudItem ->
						def clusterId = clusters?.find { it.externalId == cloudItem.status?.cluster_reference?.uuid}?.id
						def networkType = networkTypes?.find { it.externalType == cloudItem.status.resources.subnet_type }
						def networkConfig = [
								owner       : new Account(id: cloud.owner.id),
								category    : "nutanix.prism.network.${cloud.id}",
								name        : cloudItem.status.name,
								displayName : cloudItem.status.name,
								code        : "nutanix.prism.network.${cloud.id}.${cloudItem.metadata.uuid}",
								uniqueId    : cloudItem.metadata.uuid,
								externalId  : cloudItem.metadata.uuid,
								dhcpServer  : true,
								externalType: cloudItem.status.resources.subnet_type,
								type        : networkType,
								refType     : 'ComputeZone',
								refId       : cloud.id,
								zonePoolId  : clusterId,
								active      : true
						]
						Network add = new Network(networkConfig)
						networkConfig.assignedZonePools = [new ComputeZonePool(id: clusterId)]
						adds << add

					}
					morpheusContext.cloud.network.create(adds).blockingGet()
				}.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
					List<Network> itemsToUpdate = []
					for (item in updateItems) {
						def masterItem = item.masterItem
						Network existingItem = item.existingItem
						def clusterId = clusters?.find { it.externalId == masterItem.status?.cluster_reference?.uuid}?.id
						def save = false
						if (existingItem) {
							if (existingItem.zonePoolId != clusterId) {
								existingItem.zonePoolId = clusterId
								save = true
							}
							def name = masterItem.status.name
							if (existingItem.name != name) {
								existingItem.name = name
								save = true
							}
							def networkType = masterItem.status.resources.subnet_type
							if (existingItem.externalType != networkType) {
								existingItem.externalType = networkType
								save = true
							}
							if (!existingItem.type) {
								existingItem.type = networkTypes?.find { it.externalType == networkType }
								save = true
							}
							if (!existingItem.assignedZonePools?.find { it.id == clusterId }) {
								existingItem.assignedZonePools += new ComputeZonePool(id: clusterId)
								save = true
							}
							if (save) {
								itemsToUpdate << existingItem
							}
						}
					}
					if (itemsToUpdate.size() > 0) {
						morpheusContext.cloud.network.save(itemsToUpdate).blockingGet()
					}

				}.onDelete { removeItems ->
					morpheusContext.cloud.network.remove(removeItems).blockingGet()
				}.start()

			}

		} catch(e) {
			log.error "Error in execute of NetworksSync: ${e}", e
		}
		log.debug "END: execute NetworksSync: ${cloud.id}"
	}
}
