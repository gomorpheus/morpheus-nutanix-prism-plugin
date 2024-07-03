/*
 * Copyright 2024 Morpheus Data, LLC.
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

package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ProjectsSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient
	private Map selectedProject
	private ArrayList allProjects

	public ProjectsSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient, Map projects) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
		this.selectedProject = projects.selected as Map
		this.allProjects = projects.all as ArrayList
	}

	public static getProjectCategory(Cloud cloud) {
		return "nutanix.prism.project.${cloud.id}"
	}

	def execute() {
		log.debug "BEGIN: execute ProjectsSync: ${cloud.id}"
		try {
			def masterData = filterProjects(allProjects, selectedProject?.uuid)
			if(!selectedProject?.uuid) {
				masterData.data << [
				    metadata: [
				        uuid: "${cloud.id}.none",
				        name: "None",
						default: true
				    ]
				]
			}
			if(masterData.success) {
				Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, getProjectCategory(cloud), null)
				SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, masterData.data)
				syncTask.addMatchFunction { CloudPoolIdentity domainObject, Map apiItem ->
					domainObject.externalId == apiItem.metadata.uuid
				}.onDelete { removeItems ->
					removeMissingProjects(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<CloudPool, Map>> updateItems ->
					updateMatchedProjects(updateItems)
				}.onAdd { itemsToAdd ->
					addMissingProjects(itemsToAdd)
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
		log.debug "END: execute ProjectsSync: ${cloud.id}"
	}

	def addMissingProjects(Collection<Map> addList) {
		log.debug "addMissingProjects ${cloud} ${addList.size()}"
		def adds = []

		for(cloudItem in addList) {
			def poolConfig = [
					owner     : cloud.owner,
					type      : 'Project',
					name      : cloudItem.metadata.name,
					externalId: cloudItem.metadata.uuid,
					uniqueId  : cloudItem.metadata.uuid,
					internalId: cloudItem.metadata.name,
					refType   : 'ComputeZone',
					refId     : cloud.id,
					cloud     : cloud,
					category  : getProjectCategory(cloud),
					code      : "${getProjectCategory(cloud)}.${cloudItem.metadata.uuid}",
					active    : cloud.defaultPoolSyncActive
			]
			if(cloudItem.metadata.default) {
				poolConfig.defaultPool = true
			}
			def add = new CloudPool(poolConfig)
			adds << add
		}

		if(adds) {
			morpheusContext.async.cloud.pool.bulkCreate(adds).blockingGet()
		}
	}

	private updateMatchedProjects(List updateList) {
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

	private removeMissingProjects(List<CloudPoolIdentity> removeList) {
		log.debug "removeMissingProjects: ${removeList?.size()}"
		morpheusContext.async.cloud.pool.bulkRemove(removeList).blockingGet()
	}


	private filterProjects(projects, projectUuid) {
		log.debug "getProjects"
		def rtn = [success: true, data: []]
		try {
			if (projects) {
				if(projectUuid) {
					projects = projects.findAll{it.metadata?.uuid == projectUuid}
				}
				rtn.data = projects
			}
		} catch(e) {
			rtn.success = false
			log.error "Error in getting projects: ${e}", e
		}
		rtn
	}
}
