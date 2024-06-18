package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable
@Slf4j
class ClustersSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient
	private Map selectedProject
	private ArrayList allProjects


	public ClustersSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient, Map projects) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
		this.selectedProject = projects.selected as Map
		this.allProjects = projects.all as ArrayList

	}

	def execute() {
		log.debug "BEGIN: execute ClustersSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def listResults = getClusters(authConfig, selectedProject?.cluster_reference_list)

			def projects = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { CloudPoolIdentity projection ->
				return projection.type == 'Project' && projection.internalId != null
			}.toList().blockingGet()

			def projectPlaceholderId = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).filter { CloudPoolIdentity projection ->
				return projection.type == 'Project' && projection.externalId == "${cloud.id}.none"
			}.toList().blockingGet()[0]?.id

			def clusterProjectsMapping = [:]
			if(allProjects) {
				allProjects.each { project ->
					def clusterList = project.status?.resources?.cluster_reference_list
					if(clusterList) {
						clusterList.each { cluster ->
							def projectMatch = projects.find{it.externalId == project.metadata?.uuid}
							if(projectMatch) {
								if (clusterProjectsMapping[cluster.uuid])
									clusterProjectsMapping[cluster.uuid] += projectMatch.id
								else
									clusterProjectsMapping[cluster.uuid] = [projectMatch.id]
							}
						}
					}
				}
			}
			if(listResults.success) {
				def masterHosts = listResults?.data?.findAll { cloudItem ->
					cloudItem.status?.resources?.config?.service_list?.contains('AOS')
				} ?: []
				Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, "nutanix.prism.cluster.${cloud.id}", null)
				SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, masterHosts)
				syncTask.addMatchFunction { CloudPoolIdentity domainObject, Map apiItem ->
					domainObject.externalId == apiItem.metadata.uuid
				}.onDelete { removeItems ->
					removeMissingResourcePools(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<CloudPool, Map>> updateItems ->
					updateMatchedResourcePools(updateItems, clusterProjectsMapping, projectPlaceholderId)
				}.onAdd { itemsToAdd ->
					addMissingResourcePools(itemsToAdd, clusterProjectsMapping, projectPlaceholderId)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>).map {CloudPool cloudPool ->
						SyncTask.UpdateItemDto<CloudPool, Map> matchItem = updateItemMap[cloudPool.id]
						return new SyncTask.UpdateItem<CloudPool,Map>(existingItem:cloudPool, masterItem:matchItem.masterItem)
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute ClustersSync: ${cloud.id}"
	}

	def addMissingResourcePools(List addList, Map clusterProjectsMapping, Long projectPlaceholderId) {
		log.debug "addMissingResourcePools ${cloud} ${addList.size()}"
		def adds = []

		for(cloudItem in addList) {
			def clusterData = cloudItem.status
			def poolConfig = [
					owner     : cloud.owner,
					type      : 'Cluster',
					name      : clusterData.name,
					externalId: cloudItem.metadata.uuid,
					uniqueId  : cloudItem.metadata.uuid,
					internalId: clusterData.name,
					refType   : 'ComputeZone',
					refId     : cloud.id,
					cloud     : cloud,
					category  : "nutanix.prism.cluster.${cloud.id}",
					code      : "nutanix.prism.cluster.${cloud.id}.${cloudItem.metadata.uuid}",
					readOnly  : true,
					active    : cloud.defaultPoolSyncActive
			]

			def add = new CloudPool(poolConfig)
			def projectIds = []
			if(projectPlaceholderId) {
				projectIds << projectPlaceholderId
			}
			if(clusterProjectsMapping[cloudItem.metadata.uuid]){
				projectIds += clusterProjectsMapping[cloudItem.metadata.uuid]
			}
			if(projectIds) {
				add.setConfigProperty('associatedProjectIds', projectIds)
			}
			adds << add
		}

		if(adds) {
			morpheusContext.async.cloud.pool.bulkCreate(adds).blockingGet()
		}
	}

	private updateMatchedResourcePools(List updateList, Map clusterProjectsMapping, Long projectPlaceholderId) {
		log.debug "updateMatchedResourcePools: ${cloud} ${updateList.size()}"
		def updates = []
		
		for(update in updateList) {
			def matchItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false

			if(existing.name != matchItem.status.name) {
				existing.name = matchItem.status.name
				save = true
			}
			def projectIds = []
			if(projectPlaceholderId) {
				projectIds << projectPlaceholderId
			}
			if(clusterProjectsMapping[matchItem.metadata.uuid]){
				projectIds += clusterProjectsMapping[matchItem.metadata.uuid]
			}
			if(projectIds && existing.getConfigProperty('associatedProjectIds') != projectIds){
				existing.setConfigProperty('associatedProjectIds', projectIds)
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

	private removeMissingResourcePools(List<CloudPoolIdentity> removeList) {
		log.debug "removeMissingResourcePools: ${removeList?.size()}"
		morpheusContext.async.cloud.pool.bulkRemove(removeList).blockingGet()
	}

	private getClusters(authConfig, clusterList) {
		log.debug "getClusters"
		def rtn = [success: true, data: []]
		try {
			ServiceResponse listResult = NutanixPrismComputeUtility.listClusters(apiClient, authConfig)
			if (listResult.success) {
				def clusters = listResult.data
				if(clusterList?.size() > 0) {
					def allowedClusterUuids = clusterList.collect { it.uuid }
					clusters = clusters.findAll{allowedClusterUuids.contains(it.metadata?.uuid)}
				}
				rtn.data = clusters
			} else {
				rtn.success = false
				log.warn "Error getting list of clusters: ${listResult.msg}"
			}
		} catch(e) {
			rtn.success = false
			log.error "Error in getting clusters: ${e}", e
		}
		rtn
	}
}
