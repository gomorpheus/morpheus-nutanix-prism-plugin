package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismSyncUtils
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class TemplatesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public TemplatesSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute TemplatesSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def listResults = NutanixPrismComputeUtility.listTemplates(apiClient, authConfig)
			if (listResults.success) {
				def masterTemplates = listResults?.data?.data ?: []
				Observable<VirtualImageLocationIdentityProjection> domainRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(new DataQuery().withFilters([
					new DataFilter("refType", "ComputeZone"),
					new DataFilter("refId", cloud.id),
					new DataFilter("virtualImage.externalType", "template")
				]).withJoins('virtualImage'))
				SyncTask<VirtualImageLocationIdentityProjection, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, masterTemplates)
				syncTask.addMatchFunction { VirtualImageLocationIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.extId
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.virtualImage.location.listById(updateItems?.collect { it.existingItem.id }).map { VirtualImageLocation virtualImageLocation ->
						SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, Map> matchItem = updateItemMap[virtualImageLocation.id]
						return new SyncTask.UpdateItem<Datastore, Map>(existingItem: virtualImageLocation, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					addMissingVirtualImageLocations(itemsToAdd)
				}.onUpdate { List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateItems ->
					updateMatchedVirtualImageLocations(updateItems)
				}.onDelete { removeItems ->
					removeMissingVirtualImageLocations(removeItems)
				}.start()
			}

		} catch(e) {
			log.error "Error in execute of TemplatesSync: ${e}", e
		}
		log.debug "END: execute TemplatesSync: ${cloud.id}"
	}

	def addMissingVirtualImageLocations(Collection<Map> objList) {
		log.debug "Templates - addMissingVirtualImageLocations: ${objList?.size()}"
		

		def names = objList.collect{it.templateName}?.unique()
		def allowedImageTypes = ['qcow2']

		Observable domainRecords = morpheusContext.async.virtualImage.listIdentityProjections(new DataQuery().withFilters([
			new DataFilter<String>("imageType", "in", allowedImageTypes),
			new DataFilter<Collection<String>>("name", "in", names),
			new DataOrFilter(
				new DataFilter<Boolean>("systemImage", true),
				new DataOrFilter(
					new DataFilter("owner", null),
					new DataFilter<Long>("owner.id", cloud.owner.id)
				)
			)
		]))
		SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask = new SyncTask<>(domainRecords, objList)
		syncTask.addMatchFunction { VirtualImageIdentityProjection domainObject, Map cloudItem ->
			domainObject.name == cloudItem.templateName
		}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItems ->
			Map<Long, SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
			morpheusContext.async.virtualImage.listById(updateItems?.collect { it.existingItem.id }).map { VirtualImage virtualImage ->
				SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map> matchItem = updateItemMap[virtualImage.id]
				return new SyncTask.UpdateItem<VirtualImage, Map>(existingItem: virtualImage, masterItem: matchItem.masterItem)
			}
		}.onAdd { itemsToAdd ->
			addMissingVirtualImages(itemsToAdd)
		}.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
			// Found the VirtualImage for this location.. just need to create the location
			addMissingVirtualImageLocationsForImages(updateItems)
		}.start()
	}

	private addMissingVirtualImages(Collection<Map> addList) {
		log.debug "Templates - addMissingVirtualImages ${addList?.size()}"

		def adds = []
		def addExternalIds = []
		addList?.each {
			def imageConfig = buildVirtualImageConfig(it)
			def add = new VirtualImage(imageConfig)
			def locationConfig = buildLocationConfig(add)
			VirtualImageLocation location = new VirtualImageLocation(locationConfig)
			add.imageLocations = [location]
			addExternalIds << add.externalId
			VirtualImage savedImage = morpheusContext.async.virtualImage.create(add, cloud).blockingGet()
			def savedLocation = savedImage.imageLocations.find {it.externalId == locationConfig.externalId}
			if (!savedLocation) {
				log.error "Error in creating template ${add}"
			} else {
				performPostSaveSync(savedLocation, it)
			}
		}
	}

	private addMissingVirtualImageLocationsForImages(List<SyncTask.UpdateItem<VirtualImage, Map>> addItems) {
		log.debug "Templates - addMissingVirtualImageLocationsForImages ${addItems?.size()}"
		

		def locationAdds = []
		addItems?.each { add ->
			VirtualImage virtualImage = add.existingItem
			def uuid = add.masterItem.metadata?.uuid
			def locationConfig = [
				virtualImage: virtualImage,
				code        : "nutanix.prism.image.${cloud.id}.${uuid}",
				internalId  : uuid,
				externalId  : uuid,
				imageName   : virtualImage.name,
				imageRegion : cloud.regionCode,
				isPublic    : false,
				refType     : 'ComputeZone',
				refId       : cloud.id
			]
			VirtualImageLocation location = new VirtualImageLocation(locationConfig)
			VirtualImageLocation savedLocation = morpheusContext.async.virtualImage.location.create(location, cloud).blockingGet()
			if (!savedLocation) {
				log.error "Error in creating template ${location}"
			} else {
				performPostSaveSync(savedLocation, add.masterItem)
			}
		}

		if(locationAdds) {
			log.debug "About to create ${locationAdds.size()} locations"

			morpheusContext.async.virtualImage.location.create(locationAdds, cloud).blockingGet()
		}
	}

	private updateMatchedVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateList) {
		log.debug "Templates - updateMatchedVirtualImages: ${cloud} ${updateList.size()}"

		def saveLocationList = []
		def saveImageList = []
		def virtualImagesById = morpheusContext.async.virtualImage.listById(updateList.collect { it.existingItem.virtualImage.id }).toMap {it.id}.blockingGet()

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def virtualImage = virtualImagesById[existingItem.virtualImage.id]
			def cloudItem = updateItem.masterItem
			def virtualImageConfig = buildVirtualImageConfig(cloudItem)
			def specString = cloudItem.templateVersionSpec.vmSpec
			def diskList = []
			try {
				if(specString) {
					def parsedSpec = new JsonSlurper().parseText(specString)
					diskList = parsedSpec?.spec?.resources?.disk_list ?: []
					diskList = diskList.findAll { it.device_properties.device_type != "CDROM"}
				}

			} catch (e) {
				log.debug("Error saving template disks ${e}")

			}
			def save = false
			def saveImage = false
			def state = 'Active'
			
			def imageName = virtualImageConfig.name
			if(existingItem.imageName != imageName) {
				existingItem.imageName = imageName

				if(virtualImage.imageLocations?.size() < 2) {
					virtualImage.name = imageName
					saveImage = true
				}
				save = true
			}
			if(existingItem.externalId != virtualImageConfig.externalId) {
				existingItem.externalId = virtualImageConfig.externalId
				save = true
			}
			if(virtualImage.status != state) {
				virtualImage.status = state
				saveImageList << virtualImage
			}
			if (existingItem.imageRegion != cloud.regionCode) {
				existingItem.imageRegion = cloud.regionCode
				save = true
			}
			if (virtualImage.imageRegion != virtualImageConfig.imageRegion) {
				virtualImage.imageRegion = virtualImageConfig.imageRegion
				saveImage = true
			}

			if(virtualImage.systemImage == null) {
				virtualImage.systemImage = false
				saveImage = true
			}

			def syncResults = NutanixPrismSyncUtils.syncVolumes(existingItem, diskList, cloud, morpheusContext)
			if(syncResults.changed) {
				save = true
			}

			if(save) {
				saveLocationList << existingItem
			}

			if(saveImage) {
				saveImageList << virtualImage
			}
		}

		if(saveLocationList) {
			morpheusContext.async.virtualImage.location.save(saveLocationList, cloud).blockingGet()
		}
		if(saveImageList) {
			morpheusContext.async.virtualImage.save(saveImageList.unique(), cloud).blockingGet()
		}
	}

	private removeMissingVirtualImageLocations(List removeList) {
		log.debug "removeMissingVirtualImageLocations: ${removeList?.size()}"
		morpheusContext.async.virtualImage.location.remove(removeList).blockingGet()
	}

	private buildVirtualImageConfig(Map cloudItem) {
		Account account = cloud.account
		def regionCode = cloud.regionCode

		def uuid = cloudItem.extId

		def imageConfig = [
				account      : account,
				category     : "nutanix.prism.image.${cloud.id}",
				name         : cloudItem.templateName,
				code         : "nutanix.prism.image.${cloud.id}.${uuid}",
			    imageType    : ImageType.qcow2,
				status       : 'Active',
				isPublic     : false,
				externalId   : uuid,
				externalType : 'template',
				imageRegion  : regionCode,
				internalId   : uuid,
				uniqueId     : uuid,
			    systemImage  : false
		]

		return imageConfig
	}

	private Map buildLocationConfig(VirtualImage image) {
		return [
				virtualImage: image,
				code        : "nutanix.prism.image.${cloud.id}.${image.externalId}",
				internalId  : image.internalId,
				externalId  : image.externalId,
				imageName   : image.name,
				imageRegion : cloud.regionCode,
				isPublic    : false,
				refType     : 'ComputeZone',
			    refId       : cloud.id
		]
	}

	private Map buildLocationConfig(VirtualImageIdentityProjection imageLocationProj) {
		return [
				virtualImage: new VirtualImage(id: imageLocationProj.id),
				code        : "nutanix.prism.image.${cloud.id}.${imageLocationProj.externalId}",
				internalId  : imageLocationProj.externalId, // internalId and externalId match
				externalId  : imageLocationProj.externalId,
				imageName   : imageLocationProj.name,
				imageRegion : cloud.regionCode
		]
	}

	private performPostSaveSync(location, cloudItem) {
		def specString = cloudItem.templateVersionSpec.vmSpec
		def diskList = []
		try {
			if(specString) {
				def parsedSpec = new JsonSlurper().parseText(specString)
				diskList = parsedSpec?.spec?.resources?.disk_list ?: []
				diskList = diskList.findAll { it.device_properties.device_type != "CDROM"}
			}

		} catch (e) {
			log.debug("Error saving template disks ${e}")
		}
		def syncResults = NutanixPrismSyncUtils.syncVolumes(location, diskList, cloud, morpheusContext)
		if(syncResults.changed) {
			morpheusContext.async.virtualImage.location.save([location] as List<VirtualImageLocation>, cloud).blockingGet()
		}
	}

}
