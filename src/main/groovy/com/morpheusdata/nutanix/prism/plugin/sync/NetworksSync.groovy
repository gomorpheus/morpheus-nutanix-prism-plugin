package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.Network
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismSyncUtils
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class NetworksSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient
	private Map selectedProject
	private ArrayList allProjects


	public NetworksSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient, Map projects) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
		this.selectedProject = projects.selected as Map
		this.allProjects = projects.all as ArrayList
	}

	def execute() {
		log.debug "BEGIN: execute NetworksSync: ${cloud.id}"
		try {
			def networkTypes = plugin.cloudProvider.getNetworkTypes()

			def clusters = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { CloudPoolIdentity projection ->
				return projection.type == 'Cluster' && projection.internalId != null
			}.toList().blockingGet()

			def vpcs = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { CloudPoolIdentity projection ->
				return projection.type == 'VPC' && projection.internalId != null
			}.toList().blockingGet()

			def projectPlaceholder = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { CloudPoolIdentity projection ->
				return projection.type == 'Project' && projection.externalId == "${cloud.id}.none"
			}.toList().blockingGet()[0]

			def projects = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { CloudPoolIdentity projection ->
				return projection.type == 'Project' && projection.internalId != null
			}.toList().blockingGet()

			def authConfig = plugin.getAuthConfig(cloud)
			def networkProjectsMapping = [:]
			if(allProjects) {
				allProjects.each { project ->
					def subnetList = project.status?.resources?.subnet_reference_list
					if(subnetList) {
						subnetList.each { subnet ->
							def projectMatch = projects.find{it.externalId == project.metadata?.uuid}
							if(projectMatch) {
								if (networkProjectsMapping[subnet.uuid])
									networkProjectsMapping[subnet.uuid] += projectMatch
								else
									networkProjectsMapping[subnet.uuid] = [projectMatch]
							}
						}
					}
				}
			}

			def listResults = getNetworks(authConfig, selectedProject?.subnet_reference_list)
			if (listResults.success) {
				def domainRecords = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id)
				SyncTask<NetworkIdentityProjection, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, listResults.data)
				syncTask.addMatchFunction { NetworkIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.metadata?.uuid
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.cloud.network.listById(updateItems?.collect { it.existingItem.id }).map { Network network ->
						SyncTask.UpdateItemDto<NetworkIdentityProjection, Map> matchItem = updateItemMap[network.id]
						return new SyncTask.UpdateItem<NetworkIdentityProjection, Map>(existingItem: network, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					def networkAdds = []
					itemsToAdd?.each { cloudItem ->
						def cluster = clusters?.find { it.externalId == cloudItem.status?.cluster_reference?.uuid}
						def clusterId = cluster?.id
						def vpc = vpcs?.find { it.externalId == cloudItem.spec?.resources?.vpc_reference?.uuid}
						def cloudPoolId = vpc?.id ?: clusterId
						def networkTypeString = cloudItem.status.resources.subnet_type
						def managed = cloudItem.status.resources?.ip_config?.subnet_ip ? true : false
						def networkType = networkTypes?.find { it.code == "nutanix-prism-unmanaged-vlan-network" }
						if(networkTypeString == 'OVERLAY') {
							networkType = networkTypes?.find { it.code == "nutanix-prism-overlay-network" }
						} else if(networkTypeString == 'VLAN' && managed) {
							networkType = networkTypes?.find { it.code == "nutanix-prism-vlan-network" }
						}
						def networkConfig = [
								owner       : new Account(id: cloud.owner.id),
								category    : "nutanix.prism.network.${cloud.id}",
								name        : cloudItem.status.name,
								displayName : cloudItem.status.name,
								code        : "nutanix.prism.network.${cloud.id}.${cloudItem.metadata.uuid}",
								uniqueId    : cloudItem.metadata.uuid,
								externalId  : cloudItem.metadata.uuid,
								dhcpServer  : true,
								externalType: networkTypeString,
								type        : networkType,
								refType     : 'ComputeZone',
								refId       : cloud.id,
								cloudPool   : new CloudPool(id: cloudPoolId),
								active      : true
						]

						if(clusterId) {
							networkConfig.displayName = "${cloudItem.status.name} ${cluster.name}"
						}

						Network networkAdd = new Network(networkConfig)
						if(networkTypeString == 'OVERLAY') {
							networkAdd.setConfigProperty("vpc", cloudItem.spec.resources.vpc_reference.uuid)
						}
						networkConfig.assignedZonePools = []
						if(clusterId) {
							networkConfig.assignedZonePools += new CloudPool(id: clusterId)
						}
						if(projectPlaceholder) {
							networkConfig.assignedZonePools += new CloudPool(id: projectPlaceholder.id)
						}
						if(networkProjectsMapping[cloudItem.metadata.uuid]) {
							networkConfig.assignedZonePools += networkProjectsMapping[cloudItem.metadata.uuid]
						}
						networkAdds << networkAdd
					}
					//create networks
					morpheusContext.async.cloud.network.create(networkAdds).blockingGet()
				}.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
					List<Network> itemsToUpdate = []
					for (item in updateItems) {
						def masterItem = item.masterItem
						Network existingItem = item.existingItem
						def cluster = clusters?.find { it.externalId == masterItem.status?.cluster_reference?.uuid}
						def clusterId = cluster?.id
						def save = false
						if (existingItem) {

							if (existingItem.cloudPool?.id != clusterId) {
								existingItem.cloudPool = new CloudPool(id: clusterId)
								save = true
							}
							def name = masterItem.status.name
							if (existingItem.name != name) {
								existingItem.name = name
								if(clusterId) {
									existingItem.displayName = "${name} ${cluster.name}"
								} else {
									existingItem.displayName = name
								}
								save = true
							}
							def networkTypeString = masterItem.status.resources.subnet_type
							def managed = masterItem.status.resources?.ip_config?.subnet_ip ? true : false
							def networkType = networkTypes?.find { it.code == "nutanix-prism-unmanaged-vlan-network" }
							if(networkTypeString == 'OVERLAY') {
								networkType = networkTypes?.find { it.code == "nutanix-prism-overlay-network" }
							} else if(networkTypeString == 'VLAN' && managed) {
								networkType = networkTypes?.find { it.code == "nutanix-prism-vlan-network" }
							}
							if (existingItem.externalType != networkTypeString) {
								existingItem.externalType = networkTypeString
								save = true
							}
							if (existingItem.type != networkType) {
								existingItem.type = networkType
								save = true
							}

							def zonePools = []
							if(networkProjectsMapping[masterItem.metadata.uuid]) {
								zonePools += networkProjectsMapping[masterItem.metadata.uuid]
							}
							if(clusterId) {
								zonePools += new CloudPool(id: clusterId)
							}
							if(projectPlaceholder) {
								zonePools += new CloudPool(id: projectPlaceholder.id)
							}
							def zonePoolSyncLists = NutanixPrismSyncUtils.buildSyncLists(existingItem.assignedZonePools, zonePools, { e, m ->  {e.id == m.id}})
							if(zonePoolSyncLists.addList.size() > 0) {
								existingItem.assignedZonePools += zonePoolSyncLists.addList
								save = true
							}

							if (save) {
								itemsToUpdate << existingItem
							}
						}
					}
					if (itemsToUpdate.size() > 0) {
						morpheusContext.async.cloud.network.save(itemsToUpdate).blockingGet()
					}

				}.onDelete { removeItems ->
					morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
				}.start()

			}

		} catch(e) {
			log.error "Error in execute of NetworksSync: ${e}", e
		}
		log.debug "END: execute NetworksSync: ${cloud.id}"
	}

	private getNetworks(authConfig, networkList) {
		log.debug "getNetworks"
		def rtn = [success: true, data: []]
		try {
			ServiceResponse listResult = NutanixPrismComputeUtility.listNetworks(apiClient, authConfig)
			if (listResult.success) {
				def networks = listResult.data
				if(networkList?.size() > 0) {
					def allowedNetworkUuids = networkList.collect { it.uuid }
					networks = networks.findAll{allowedNetworkUuids.contains(it.metadata?.uuid)}
				}
				rtn.data = networks
			} else {
				rtn.success = false
				log.warn "Error getting list of networks: ${listResult.msg}"
			}
		} catch(e) {
			rtn.success = false
			log.error "Error in getting networks: ${e}", e
		}
		rtn
	}
}
