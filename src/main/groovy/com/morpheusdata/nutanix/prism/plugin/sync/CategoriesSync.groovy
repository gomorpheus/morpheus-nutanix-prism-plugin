package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class CategoriesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public CategoriesSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute Categories: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def masterData = getCategoriesAndValues(authConfig)
			if(masterData.success) {
				Observable<ReferenceDataSyncProjection> domainRecords = morpheusContext.cloud.listReferenceDataByCategory(cloud, getCategory())
				SyncTask<ReferenceDataSyncProjection, Map, com.morpheusdata.model.ReferenceData> syncTask = new SyncTask<>(domainRecords, masterData.data)
				syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, Map data ->
					domainObject.externalId == data.display
				}.onDelete { removeItems ->
					removeMissingCategories(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, Map>> updateItems ->
					// Nothing to do
				}.onAdd { itemsToAdd ->
					addMissingCategories(itemsToAdd)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.cloud.listReferenceDataById(updateItems.collect { it.existingItem.id } as List<Long>).map {ReferenceData referenceData ->
						SyncTask.UpdateItemDto<ReferenceData, Map> matchItem = updateItemMap[referenceData.id]
						return new SyncTask.UpdateItem<ReferenceData,Map>(existingItem:referenceData, masterItem:matchItem.masterItem)
					}
				}.start()
			}
		} catch(e) {
			log.error "Error in execute : ${e}", e
		}
		log.debug "END: execute Categories: ${cloud.id}"
	}

	def addMissingCategories(List addList) {
		log.debug "addMissingCategories ${cloud} ${addList.size()}"
		def adds = []

		for(Map data in addList) {
			Map props = [
					code      : "nutanix.prism.categories.${cloud.id}.${data.display}",
					category  : getCategory(),
					name      : data.name,
					keyValue  : data.value,
					externalId: data.display,
					value     : data.value
			]

			def add = new ReferenceData(props)
			adds << add
		}

		if(adds) {
			morpheusContext.cloud.create(adds, cloud, getCategory()).blockingGet()
		}
	}

	private removeMissingCategories(List<ReferenceData> removeList) {
		log.debug "removeMissingCategories: ${removeList?.size}"
		morpheusContext.cloud.remove(removeList).blockingGet()
	}

	private getCategory() {
		return "nutanix.prism.categories.${cloud.id}"
	}
	
	private getCategoriesAndValues(authConfig) {
		log.debug "getCategoriesAndValues"
		def rtn = [success: true, data: []]
		try {
			ServiceResponse listResult = NutanixPrismComputeUtility.listCategories(apiClient, authConfig)
			if (listResult.success) {
				def categoryNames = listResult.data?.collect { it.name }
				for(categoryName in categoryNames) {
					log.debug "Getting values for ${categoryName}"
					ServiceResponse valueResponse = NutanixPrismComputeUtility.listCategoryValues(apiClient, authConfig, categoryName)
					if(valueResponse.success) {
						valueResponse.data?.each { payload -> 
							rtn.data << [name: categoryName, value: payload.value, display: "${categoryName}:${payload.value}"]
						}
					} else {
						log.warn "Error getting category values for: ${categoryName}"
					}
				}
			} else {
				log.warn "Error getting list of categories: ${listResult.msg}"
			}
		} catch(e) {
			rtn.success = false
			log.error "Error in getting categories and values: ${e}", e
		}
		rtn
	}
}
