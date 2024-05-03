package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ImagesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient

	public ImagesSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
	}

	def execute() {
		log.debug "BEGIN: execute ImagesSync: ${cloud.id}"
		try {
			def authConfig = plugin.getAuthConfig(cloud)
			def listResults = NutanixPrismComputeUtility.listImages(apiClient, authConfig)
			if (listResults.success) {
				def masterImages = listResults?.data?.findAll { it.status.resources.image_type != 'ISO_IMAGE' }
				Observable domainRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(new DataQuery().withFilters([
					new DataFilter("refType", "ComputeZone"),
					new DataFilter("refId", cloud.id),
					new DataFilter("sharedStorage", false),
					new DataOrFilter(
						new DataFilter("virtualImage.externalType","!=", "template"),
						new DataFilter("virtualImage.externalType", "null")
					)
				]).withJoins('virtualImage'))
				SyncTask<VirtualImageLocationIdentityProjection, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, masterImages)
				syncTask.addMatchFunction { VirtualImageLocationIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem?.metadata?.uuid
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
			log.error "Error in execute of ImagesSync: ${e}", e
		}
		log.debug "END: execute ImagesSync: ${cloud.id}"
	}

	def addMissingVirtualImageLocations(Collection<Map> objList) {
		log.debug "addMissingVirtualImageLocations: ${objList?.size()}"

		def names = objList.collect{it.status.name}?.unique()
		List<VirtualImageIdentityProjection> existingItems = []
		def allowedImageTypes = ['qcow2']

		def uniqueIds = [] as Set
		Observable domainRecords = morpheusContext.async.virtualImage.listIdentityProjections(new DataQuery().withFilters([
			new DataFilter<String>("imageType", "in", allowedImageTypes),
			new DataFilter<Collection<String>>("name", "in", names),
			new DataOrFilter(
				new DataFilter("code", "=~", "nutanix.prism.image"),
				new DataFilter<Boolean>("userUploaded", true)
			),
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
			domainObject.externalId && (domainObject.externalId == cloudItem.metadata?.uuid)
		}.addMatchFunction { VirtualImageIdentityProjection domainObject, Map cloudItem ->
			!domainObject.externalId && (domainObject.name == cloudItem.status.name)
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
		}.onDelete {
			//do nothing
		}.start()
	}

	private addMissingVirtualImages(Collection<Map> addList) {
		log.debug "addMissingVirtualImages ${addList?.size()}"
		Account account = cloud.account
		def regionCode = cloud.regionCode
		def adds = []
		def addExternalIds = []
		addList?.each {
			def imageConfig = buildVirtualImageConfig(it)
			def add = new VirtualImage(imageConfig)
			def locationConfig = buildLocationConfig(add)
			VirtualImageLocation location = new VirtualImageLocation(locationConfig)
			add.imageLocations = [location]
			addExternalIds << add.externalId
			adds << add
		}

		// Create em all!
		log.debug "About to create ${adds.size()} virtualImages"
		morpheusContext.async.virtualImage.create(adds, cloud).blockingGet()

	}

	private addMissingVirtualImageLocationsForImages(List<SyncTask.UpdateItem<VirtualImage, Map>> addItems) {
		log.debug "addMissingVirtualImageLocationsForImages ${addItems?.size()}"

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
			locationAdds << location
		}

		if(locationAdds) {
			log.debug "About to create ${locationAdds.size()} locations"
			morpheusContext.async.virtualImage.location.create(locationAdds, cloud).blockingGet()
		}
	}

	private updateMatchedVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, Map>> updateList) {
		log.debug "updateMatchedVirtualImages: ${cloud} ${updateList.size()}"
		def saveLocationList = []
		def saveImageList = []
		def virtualImagesById = morpheusContext.async.virtualImage.listById(updateList.collect { it.existingItem.virtualImage.id }).toMap {it.id}.blockingGet()

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def virtualImage = virtualImagesById[existingItem.virtualImage.id]
			def cloudItem = updateItem.masterItem
			def virtualImageConfig = buildVirtualImageConfig(cloudItem)
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
			if (virtualImage.remotePath != virtualImageConfig.remotePath) {
				virtualImage.remotePath = virtualImageConfig.remotePath
				saveImage = true
			}
			if (virtualImage.imageRegion != virtualImageConfig.imageRegion) {
				virtualImage.imageRegion = virtualImageConfig.imageRegion
				saveImage = true
			}
			if (virtualImage.minDisk != virtualImageConfig.minDisk) {
				virtualImage.minDisk = virtualImageConfig.minDisk as Long
				saveImage = true
			}
			if (virtualImage.bucketId != virtualImageConfig.bucketId) {
				virtualImage.bucketId = virtualImageConfig.bucketId
				saveImage = true
			}
			if(virtualImage.systemImage == null) {
				virtualImage.systemImage = false
				saveImage = true
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

		def imageConfig = [
				account    : account,
				category   : "nutanix.prism.image.${cloud.id}",
				name       : cloudItem.status.name,
				code       : "nutanix.prism.image.${cloud.id}.${cloudItem.metadata.uuid}",
				imageType  : ImageType.qcow2,
				status     : 'Active',
				minDisk    : cloudItem.status.resources.size_bytes?.toLong(),
				isPublic   : false,
				remotePath : cloudItem.status.resources?.retrieval_uri_list?.getAt(0) ?: cloudItem.status.resources?.source_uri,
				externalId : cloudItem.metadata.uuid,
				imageRegion: regionCode,
				internalId : cloudItem.metadata.uuid,
				uniqueId   : cloudItem.metadata.uuid,
				bucketId   : cloudItem.status.resources?.current_cluster_reference_list?.getAt(0)?.uuid,
				systemImage: false
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

}
