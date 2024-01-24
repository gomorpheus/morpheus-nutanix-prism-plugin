package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.*
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
		Cloud tmpCloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
		def regionCode = tmpCloud.regionCode

		// Grab the projections.. doing a filter pass first
		ImageType[] imageTypes = [ImageType.qcow2, ImageType.ova]
		def virtualImageIds = morpheusContext.async.virtualImage.listIdentityProjections(accountId, imageTypes).filter { it.deleted == false }.map{it.id}.toList().blockingGet()


		List options = []
		if(virtualImageIds.size() > 0) {

			def query = new DataQuery().withFilters([
				new DataFilter('status', 'Active'),
				new DataFilter('id', 'in', virtualImageIds),
				new DataOrFilter(
					new DataFilter('owner.id', accountId),
					new DataFilter('owner.id', null),
					new DataFilter('visibility', 'public')
				)
			]).withJoins('locations')
			def additionalFilters = new DataOrFilter([
				new DataFilter('category', "nutanix.prism.image.${cloudId}"),
				new DataAndFilter(
					new DataFilter("refType", "ComputeZone"),
					new DataFilter("refId", cloudId)
				),
				new DataAndFilter(
					new DataFilter("locations.refType", "ComputeZone"),
					new DataFilter("locations.refId", cloudId)
				)
			])
			if (regionCode) {
				additionalFilters.withFilters([
					new DataFilter('userUploaded', true),
					new DataFilter('imageRegion', regionCode),
					new DataFilter('locations.imageRegion', regionCode)
				])
			}
			query.withFilters(additionalFilters)
			options = morpheusContext.async.virtualImage.list(query).map { [name: it.name, value: it.id] }.toList().blockingGet()
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
		def virtualImageIds = morpheusContext.async.virtualImage.listIdentityProjections(accountId, imageTypes).filter { it.deleted == false}.map{it.id}.toList().blockingGet()

		List options = []
		if(virtualImageIds.size() > 0) {
			options = morpheusContext.async.virtualImage.list(new DataQuery().withFilters([
				new DataFilter('status', 'Active'),
				new DataFilter('id', 'in', virtualImageIds),
				new DataOrFilter(
					new DataFilter('owner.id', accountId),
					new DataFilter('owner.id', null),
					new DataFilter('visibility', 'public')
				),
				new DataOrFilter(
					new DataFilter('category', '=~', 'nutanix.prism.image'),
					new DataAndFilter(
						new DataFilter('imageType', 'in', ['qcow2', 'vmdk']),
						new DataFilter('userUploaded', true)
					)
				)
			])).map {[name: it.name, value: it.id]}.toList().blockingGet()
		}

		if(options.size() > 0) {
			options = options.sort { it.name }
		}

		options
	}

	def nutanixPrismCategories(args){
		def cloudId = getCloudId(args)
		if(cloudId) {
			Cloud tmpCloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
			def options = morpheusContext.async.metadataTag.listIdentityProjections(new DataQuery().withFilters([
				new DataFilter("refType", "ComputeZone"),
				new DataFilter("refId", tmpCloud.id),
			])).map { [name: it.externalId, value: it.externalId] }.toList().blockingGet().sort({ it.name })
			return options
		} else {
			return []
		}
	}

	def nutanixPrismCluster(args){
		def cloudId = getCloudId(args)
		if(cloudId) {
			Cloud tmpCloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
			def options = morpheusContext.async.cloud.pool.listIdentityProjections(tmpCloud.id, "nutanix.prism.cluster.${tmpCloud.id}", null).map {[name: it.name, value: it.externalId]}.toList().blockingGet().sort({ it.name })
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
