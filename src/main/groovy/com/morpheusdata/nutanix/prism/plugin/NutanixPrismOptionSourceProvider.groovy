package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.sync.CategoriesSync
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismOptionSourceProvider extends AbstractOptionSourceProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext

	NutanixPrismOptionSourceProvider(NutanixPrismPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return 'nutanix-prism-option-source'
	}

	@Override
	String getName() {
		return 'Nutanix Prism Central Option Source'
	}

	@Override
	List<String> getMethodNames() {
		return new ArrayList<String>(['nutanixPrismProvisionImage', 'nutanixPrismCategories', 'nutanixPrismCluster', 'nutanixPrismNodeImage'])
	}

	def nutanixPrismProvisionImage(args) {
		log.debug "nutanixPrismProvisionImage: ${args}"
		def cloudId = args?.size() > 0 ? args.getAt(0).zoneId.toLong() : null
		def accountId = args?.size() > 0 ? args.getAt(0).accountId.toLong() : null
		Cloud tmpCloud = morpheusContext.cloud.getCloudById(cloudId).blockingGet()
		def regionCode = tmpCloud.regionCode

		// Grab the projections.. doing a filter pass first
		ImageType[] imageTypes = [ImageType.qcow2, ImageType.ova]
		def virtualImageIds = morpheusContext.virtualImage.listSyncProjections(accountId, imageTypes).filter { it.deleted == false }.map{it.id}.toList().blockingGet()

		List options = []
		if(virtualImageIds.size() > 0) {
			def invalidStatus = ['Saving', 'Failed', 'Converting']

			morpheusContext.virtualImage.listById(virtualImageIds).blockingSubscribe { VirtualImage img ->
				if (!(img.status in invalidStatus) &&
						(img.visibility == 'public' || img.ownerId == accountId || img.ownerId == null || img.account.id == accountId)) {
					if(img.category == "nutanix.prism.image.${cloudId}" ||
							(img.refType == 'ComputeZone' && img.refId == cloudId ) ||
							img.imageLocations.any { it.refId == cloudId && it.refType == 'ComputeZone' }) {
						options << [name: img.name, value: img.id]
					}
					else if(regionCode &&
							(img.imageRegion == regionCode ||
									img.userUploaded ||
									img.imageLocations.any { it.imageRegion == regionCode }
							)
					) {
						options << [name: img.name, value: img.id]
					}
				}
			}
		}

		if(options.size() > 0) {
			options = options.sort { it.name }
		}

		options

	}

	def nutanixPrismNodeImage(args) {
		log.debug "nutanixPrismNodeImage: ${args}"
		def accountId = args?.size() > 0 ? args.getAt(0).accountId.toLong() : null

		// Grab the projections.. doing a filter pass first
		ImageType[] imageTypes = [ImageType.qcow2, ImageType.ova]
		def virtualImageIds = morpheusContext.virtualImage.listSyncProjections(accountId, imageTypes).filter { it.deleted == false}.map{it.id}.toList().blockingGet()

		List options = []
		if(virtualImageIds.size() > 0) {
			def invalidStatus = ['Saving', 'Failed', 'Converting']
			morpheusContext.virtualImage.listById(virtualImageIds).blockingSubscribe { VirtualImage img ->
				if (!(img.status in invalidStatus) &&
						(img.visibility == 'public' || img.ownerId == accountId || img.ownerId == null || img.account.id == accountId)) {
					if(img.category?.startsWith('nutanix.prism.image')) {
						options << [name: img.name, value: img.id]
					}
					else if((img.imageType == ImageType.qcow2 || img.imageType == ImageType.vmdk) && img.userUploaded) {
						options << [name: img.name, value: img.id]
					}
				}
			}
		}

		if(options.size() > 0) {
			options = options.sort { it.name }
		}

		options
	}

	def nutanixPrismCategories(args){
		def cloudId = getCloudId(args)
		if(cloudId) {
			Cloud tmpCloud = morpheusContext.cloud.getCloudById(cloudId).blockingGet()
			def options = morpheusContext.cloud.listReferenceDataByCategory(tmpCloud, CategoriesSync.getCategory(tmpCloud)).map { [name: it.externalId, value: it.externalId] }.toList().blockingGet().sort({ it.name })
			return options
		} else {
			return []
		}
	}

	def nutanixPrismCluster(args){
		def cloudId = getCloudId(args)
		if(cloudId) {
			Cloud tmpCloud = morpheusContext.cloud.getCloudById(cloudId).blockingGet()
			def options = morpheusContext.cloud.pool.listSyncProjections(tmpCloud.id, "nutanix.prism.cluster.${tmpCloud.id}").map {[name: it.name, value: it.externalId]}.toSortedList {it.name}.blockingGet()
			return options
		} else {
			return []
		}
	}

	private static getCloudId(args) {
		def cloudId = null
		if(args?.size() > 0) {
			def firstArg =  args.getAt(0)
			if(firstArg?.zoneId) {
				cloudId = firstArg.zoneId.toLong()
				return cloudId
			}
			if(firstArg?.domain?.zone?.id) {
				cloudId = firstArg.domain.zone.id.toLong()
				return cloudId
			}
		}
		return cloudId

	}
}
