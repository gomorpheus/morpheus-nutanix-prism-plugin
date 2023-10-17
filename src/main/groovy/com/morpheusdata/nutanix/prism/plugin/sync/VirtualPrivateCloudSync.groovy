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
class VirtualPrivateCloudSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public VirtualPrivateCloudSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	public static getVPC(Cloud cloud) {
		return "nutanix.prism.vpc.${cloud.id}"
	}

	def execute() {
		log.debug "BEGIN: execute VirtualPrivateCloudSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def masterData = getVPCs(authConfig)
			if(masterData.success) {

				Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, getVPC(cloud), null)
				SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, masterData.data)
				syncTask.addMatchFunction { CloudPoolIdentity domainObject, Map apiItem ->
					domainObject.externalId == apiItem.externalId
				}.onDelete { removeItems ->
					removeMissingVPCs(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<CloudPool, Map>> updateItems ->
					updateMatchedVPCs(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingVPCs(itemsToAdd)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>).map {CloudPool cloudPool ->
						SyncTask.UpdateItemDto<CloudPool, Map> matchItem = updateItemMap[cloudPool.id] as SyncTask.UpdateItemDto<CloudPool, Map>
						return new SyncTask.UpdateItem<CloudPool,Map>(existingItem:cloudPool, masterItem:matchItem.masterItem)
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute VirtualPrivateCloudSync: ${cloud.id}"
	}

	def addMissingVPCs(Collection<Map> addList) {
		log.debug "addMissingVPCs ${cloud} ${addList.size()}"
		def adds = []

		for(cloudItem in addList) {
			def poolConfig = [
					owner     : cloud.owner,
					type      : 'VPC',
					name      : cloudItem.name,
					externalId: cloudItem.externalId,
					uniqueId  : cloudItem.externalId,
					internalId: cloudItem.name,
					refType   : 'ComputeZone',
					refId     : cloud.id,
					cloud     : cloud,
					category  : getVPC(cloud),
					code      : "${getVPC(cloud)}.${cloudItem.externalId}"
			]
			def add = new CloudPool(poolConfig)
			adds << add
		}

		if(adds) {
			morpheusContext.async.cloud.pool.bulkCreate(adds).blockingGet()
		}
	}

	private updateMatchedVPCs(List updateList) {
		log.debug "updateMatchedVPCs: ${cloud} ${updateList.size()}"
		def updates = []

		for(update in updateList) {
			def matchItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false

			if(existing.name != matchItem.name) {
				existing.name = matchItem.name
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

	private removeMissingVPCs(List<CloudPoolIdentity> removeList) {
		log.debug "removeMissingVPCs: ${removeList?.size()}"
		morpheusContext.async.cloud.pool.bulkRemove(removeList).blockingGet()
	}


	private getVPCs(authConfig) {
		log.debug "getVPCs"
		def rtn = [success: true, data: []]
		try {
			ServiceResponse listResult = NutanixPrismComputeUtility.listVPCs(apiClient, authConfig)
			if (listResult.success) {
				rtn.data = listResult.data?.collect { [name: it.spec?.name, externalId: it.metadata?.uuid]}
			} else {
				log.warn "Error getting list of vpcs: ${listResult.msg}"
			}
		} catch(e) {
			rtn.success = false
			log.error "Error in getting vpcs: ${e}", e
		}
		rtn
	}
}
