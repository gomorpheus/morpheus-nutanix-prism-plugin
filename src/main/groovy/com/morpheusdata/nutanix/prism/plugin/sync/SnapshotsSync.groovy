package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.SnapshotIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import java.util.concurrent.TimeUnit


@Slf4j
class SnapshotsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public SnapshotsSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute SnapshotsSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def clusters = morpheusContext.cloud.pool.listSyncProjections(cloud.id, "nutanix.prism.cluster.${cloud.id}").map{it.externalId}.toList().blockingGet()
			def allResults = []
			def success = true
			for(int i = 0; i < clusters.size(); i++ ) {
				def listResults = NutanixPrismComputeUtility.listSnapshots(apiClient, authConfig, clusters[i])
				success &= listResults.success
				if(listResults.success) {
					allResults += listResults.data
				}
			}
			if (success) {
				Map vms = getAllVms()
				Observable domainRecords = morpheusContext.snapshot.listSyncProjections(cloud.id)
				SyncTask<SnapshotIdentityProjection, Map, Snapshot> syncTask = new SyncTask<>(domainRecords, allResults)
				syncTask.addMatchFunction { SnapshotIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.uuid
				}.onAdd { itemsToAdd ->
					addMissingSnapshots(itemsToAdd, vms)
				}.onUpdate { List<SyncTask.UpdateItem<Snapshot, Map>> updateItems ->
					updateMatchedSnapshots(updateItems)
				}.onDelete { removeItems ->
					removeMissingSnapshots(removeItems)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<SnapshotIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<SnapshotIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.snapshot.listByIds(updateItems.collect { it.existingItem.id } as List<Long>).map {Snapshot snapshot ->
						SyncTask.UpdateItemDto<Snapshot, Map> matchItem = updateItemMap[snapshot.id]
						return new SyncTask.UpdateItem<Snapshot,Map>(existingItem:snapshot, masterItem:matchItem.masterItem)
					}
				}start()
			}

		} catch(e) {
			log.error "Error in execute of SnapshotsSync: ${e}", e
		}
		log.debug "END: execute SnapshotsSync: ${cloud.id}"
	}

	def addMissingSnapshots(Collection addList, Map vms) {
		log.debug "addMissingSnapshots ${cloud} ${addList.size()}"

		for(cloudItem in addList) {
			def createdDate = null
			ComputeServer vm = vms[cloudItem.vm_uuid]
			if(cloudItem.created_time) {
				long milliseconds = TimeUnit.MICROSECONDS.toMillis(cloudItem.created_time)
				createdDate = new Date(milliseconds)
			}
			def snapshotConfig = [
					account         : cloud.owner,
					name            : cloudItem.snapshot_name,
					externalId      : cloudItem.uuid,
					cloud           : cloud,
					snapshotCreated : createdDate
			]

			def add = new Snapshot(snapshotConfig)
			Snapshot savedSnapshot = morpheusContext.snapshot.create(add).blockingGet()
			if (!savedSnapshot) {
				log.error "Error in creating snapshot ${add}"
			} else if(vm) {
				morpheusContext.snapshot.addSnapshot(savedSnapshot, vm).blockingGet()
			}
		}
	}

	private updateMatchedSnapshots(List updateList) {
		log.debug "updateMatchedSnapshots: ${cloud} ${updateList.size()}"
		def updates = []

		for(update in updateList) {
			def matchItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false

			if(existing.name != matchItem.snapshot_name) {
				existing.name = matchItem.snapshot_name
				save = true
			}

			if(save) {
				updates << existing
			}
		}
		if(updates) {
			morpheusContext.snapshot.save(updates).blockingGet()
		}
	}

	private removeMissingSnapshots(List<SnapshotIdentityProjection> removeList) {
		log.debug "removeMissingSnapshots: ${removeList?.size()}"
		morpheusContext.snapshot.removeSnapshots(removeList).blockingGet()
	}

	private Map getAllVms() {
		log.debug "getAllVms: ${cloud}"
		def vmIds = morpheusContext.computeServer.listSyncProjections(cloud.id).map{it.id}.toList().blockingGet()
		def vmsMap = morpheusContext.computeServer.listById(vmIds).toMap {it.externalId}.blockingGet()
		vmsMap
	}

}
