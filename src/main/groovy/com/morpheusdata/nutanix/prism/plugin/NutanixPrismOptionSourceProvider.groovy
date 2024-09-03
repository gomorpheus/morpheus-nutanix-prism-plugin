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

package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.*
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
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
		return new ArrayList<String>(['nutanixPrismProvisionImage', 'nutanixPrismCategories', 'nutanixPrismCluster', 'nutanixPrismNodeImage', 'nutanixPrismProjects', 'nutanixPrismVPC', 'supportedVmmApiVersions'])
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
				new DataFilter('active', true),
				new DataFilter('id', 'in', virtualImageIds),
				new DataOrFilter(
					new DataFilter('owner.id', accountId),
					new DataFilter('owner.id', null),
					new DataFilter('visibility', 'public')
				)
			]).withJoins('locations', 'owner')
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
			options = morpheusContext.async.virtualImage.list(query).map { [name: it.name, value: it.id, locations: it.imageLocations, userUploaded: it.userUploaded] }.toList().blockingGet()
		}

		if(options.size() > 0) {
			options = options.findAll{it.userUploaded || it.locations.size == 0 || (it.locations.find {loc -> loc.refType == "ComputeZone" && loc.refId == cloudId})}.collect {[name: it.name, value: it.value]}.sort { it.name }
		}

		options

	}

	def nutanixPrismNodeImage(args) {
		log.debug "nutanixPrismNodeImage: ${args}"
		def accountId = args?.size() > 0 ? args.getAt(0).accountId.toLong() : null

		// Grab the projections.. doing a filter pass first
		ImageType[] imageTypes = [ImageType.qcow2]
		def virtualImageIds = morpheusContext.async.virtualImage.listIdentityProjections(accountId, imageTypes).filter { it.deleted == false}.map{it.id}.toList().blockingGet()

		List options = []
		if(virtualImageIds.size() > 0) {
			options = morpheusContext.async.virtualImage.list(new DataQuery().withFilters([
				new DataFilter('active', true),
				new DataFilter('id', 'in', virtualImageIds),
				new DataOrFilter(
					new DataFilter('owner.id', accountId),
					new DataFilter('owner.id', null),
					new DataFilter('visibility', 'public')
				)
			]).withJoins('owner')).map {[name: it.name, value: it.id]}.toList().blockingGet()
		}
		if(options.size() > 0) {
			options = options.sort { it.name.toLowerCase() }
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
			def options = morpheusContext.async.cloud.pool.list(new DataQuery().withFilters([
				new DataFilter("refType", "ComputeZone"),
				new DataFilter("refId", tmpCloud.id),
				new DataFilter("category", "nutanix.prism.cluster.${tmpCloud.id}"),
				new DataFilter("active", true)
			])).toList().blockingGet()
			def projectId = getProjectId(args)
			if(projectId) {
				options = options.findAll {
					it.getConfigProperty('associatedProjectIds').collect { it.toLong() }.contains(projectId)
				}
			}
			options = options.collect {[name: it.name, value: it.externalId]}.sort({ it.name })
			return options
		} else {
			return []
		}
	}

	def supportedVmmApiVersions(args) {
		return NutanixPrismComputeUtility.VMM_API_VERSION.values()?.collect{[name: it.getDescription(), value: it.getCode()]}?.sort({it.name}) ?: []
	}

	def nutanixPrismVPC(args){
		def cloudId = getCloudId(args)
		if(cloudId) {
			Cloud tmpCloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
			def options = morpheusContext.async.cloud.pool.list(new DataQuery().withFilters([
				new DataFilter("refType", "ComputeZone"),
				new DataFilter("refId", tmpCloud.id),
				new DataFilter("category", "nutanix.prism.vpc.${tmpCloud.id}"),
				new DataFilter("active", true)
			])).toList().blockingGet()
			def projectId = getProjectId(args)
			if(projectId) {
				options = options.findAll {
					it.getConfigProperty('associatedProjectIds').collect { it.toLong() }.contains(projectId)
				}
			}
			options = options.collect {[name: it.name, value: it.externalId]}.sort({ it.name })
			return options
		} else {
			return []
		}
	}

	def nutanixPrismProjects(args) {
		Cloud cloud = loadCloud(args)
		def rtn = []
		def authConfig = plugin.getAuthConfig(cloud)
		NetworkProxy proxySettings = cloud.apiProxy
		HttpApiClient client
		client = new HttpApiClient()
		client.networkProxy = proxySettings
		if(authConfig.apiUrl) {
			authConfig.timeout = 5000
			def now = new Date().time
			def projectResult = NutanixPrismComputeUtility.listProjects(client, authConfig)
			if(projectResult.success && projectResult.data) {
				projectResult.data.each {
					rtn << [name:it.status?.name, value:it.status?.uuid, isDefault: cloud.configMap.project == it.status?.uuid]
				}
			}
		}
		rtn ?: [[name: 'No projects found: verify credentials above.', value: '-1', isDefault: true]]

	}

	private static getCloudId(args) {
		def cloudId = null
		if(args?.size() > 0) {
			def firstArg =  args.getAt(0)
			if(firstArg?.zoneId) {
				cloudId = firstArg.zoneId
			} else if(firstArg?.domain?.zone?.id) {
				cloudId = firstArg.domain.zone.id
			} else if (firstArg?.server?.zone?.id) {
				cloudId = firstArg.server.zone.id
			}
		}
		if(!cloudId) {
			cloudId = args.cloudId ?: args.zoneId
		}
		cloudId ? cloudId.toLong() : null
	}

	private static getProjectId(args) {
		def projectId = null
		if(args?.size() > 0) {
			def firstArg =  args.getAt(0)
			if(firstArg?.poolId) {
				projectId = firstArg.poolId
			} else if(firstArg?.resourcePoolId) {
				projectId = firstArg?.resourcePoolId
			}
		}
		if(!projectId) {
			if(args.poolId && args.poolId != [null]) {
				projectId = args.poolId
			} else if(args.resourcePoolId && args.resourcePoolId != [null]) {
				projectId = args.resourcePoolId
			}
		}
		if(projectId instanceof String && projectId.startsWith('pool-') ) {
			projectId = projectId.substring(5)
		}
		if(projectId && (projectId instanceof String || projectId instanceof Long)) {
			projectId =  projectId.toLong()
		}
		return projectId
	}

	private Cloud loadCloud(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		Long cloudId = getCloudId(args)
		Cloud rtn = cloudId ? morpheusContext.async.cloud.getCloudById(cloudId).blockingGet() : null
		if(!rtn) {
			rtn = new Cloud()
		}

		// load existing credentials when not passed in
		if(args.credential == null && !(args.username ?: args.config?.username)) {
			// check for passed in credentials
			if(!rtn.accountCredentialLoaded) {
				AccountCredential credentials = morpheusContext.services.accountCredential.loadCredentials(rtn)
				rtn.accountCredentialData = credentials?.data
			}
		} else {
			def url = args.apiUrl ?: args.config?.apiUrl
			url = decodeUrl(url)
			def config = [
				username: args.username ?: args.config?.username,
				password: args.password ?: args.config?.password,
				apiUrl: url
			]
			if (config.password == '*' * 12) {
				config.remove('secretKey')
			}
			rtn.setConfigMap(rtn.getConfigMap() + config)
			rtn.accountCredentialData = morpheusContext.services.accountCredential.loadCredentialConfig(args.credential, config).data
		}
		rtn.accountCredentialLoaded = true

		def proxy = args.apiProxy ? morpheusContext.async.network.networkProxy.getById(args.long('apiProxy')).blockingGet() : null
		rtn.apiProxy = proxy

		return rtn
	}

	private static decodeUrl(url) {
		if(url) {
			try {
				url = URLDecoder.decode(url, "UTF-8");
			} catch (UnsupportedEncodingException e) {}
		}
		return url
	}
}
