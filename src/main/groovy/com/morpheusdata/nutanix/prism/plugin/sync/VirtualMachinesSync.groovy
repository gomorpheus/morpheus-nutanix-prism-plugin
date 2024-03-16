package com.morpheusdata.nutanix.prism.plugin.sync

import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.WorkloadIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismSyncUtils
import com.morpheusdata.core.util.SyncUtils
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachinesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private NutanixPrismPlugin plugin
	private HttpApiClient apiClient
	private Boolean createNew
	private Map authConfig
	private Collection<ComputeServerInterfaceType> netTypes

	public VirtualMachinesSync(NutanixPrismPlugin nutanixPrismPlugin, Cloud cloud, HttpApiClient apiClient, Boolean createNew) {
		this.plugin = nutanixPrismPlugin
		this.cloud = cloud
		this.morpheusContext = nutanixPrismPlugin.morpheusContext
		this.apiClient = apiClient
		this.createNew = createNew
		this.netTypes = nutanixPrismPlugin.getCloudProvider().nutanixPrismProvisionProvider().getComputeServerInterfaceTypes()
	}

	def execute() {
		log.debug "BEGIN: execute VirtualMachinesSync: ${cloud.id} ${createNew}"
		def startTime = new Date().time
		try {
			this.authConfig = plugin.getAuthConfig(cloud)

			def listResults = NutanixPrismComputeUtility.listVMs(apiClient, authConfig)
			if(listResults.success) {
				def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter { ComputeServerIdentityProjection projection ->
					projection.computeServerTypeCode != 'nutanix-prism-hypervisor'
				}
				def blackListedNames = domainRecords.filter {it.status == 'provisioning'}.map {it.name}.toList().blockingGet()

				// To be used throughout the sync
				def defaultServerType = new ComputeServerType(code: 'nutanix-prism-unmanaged')
				Map hosts = getAllHosts()
				Map resourcePools = getAllResourcePools()
				Map networks = getAllNetworks()
				Map osTypes = getAllOsTypes()
				List plans = getAllServicePlans()
				Map tags = getAllTags()

				def usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: []]

				SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, listResults.data)
				syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
					domainObject.externalId == cloudItem.metadata.uuid
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
						SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
						return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
					}
				}.onAdd { itemsToAdd ->
					if (createNew) {
						addMissingVirtualMachines(cloud, plans, hosts, resourcePools, networks, osTypes, itemsToAdd as List, defaultServerType, blackListedNames, usageLists, tags)
					}
				}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
					updateMatchedVirtualMachines(cloud, plans, hosts, resourcePools, networks, osTypes, updateItems, usageLists, tags)
				}.onDelete { removeItems ->
					removeMissingVirtualMachines(cloud, removeItems, blackListedNames)
				}.observe().blockingSubscribe { completed ->
					log.debug "sending usage start/stop/restarts: ${usageLists}"
					morpheusContext.async.usage.startServerUsage(usageLists.startUsageIds).blockingGet()
					morpheusContext.async.usage.stopServerUsage(usageLists.stopUsageIds).blockingGet()
					morpheusContext.async.usage.restartServerUsage(usageLists.restartUsageIds).blockingGet()
					morpheusContext.async.usage.restartSnapshotUsage(usageLists.updatedSnapshotIds).blockingGet()
				}
			} else {
				log.warn("Error in getting VMs: ${listResults}")
			}
		} catch(e) {
			log.error("VirtualMachinesSync error: ${e}", e)
		}
		def endTime = new Date().time
		log.debug "END: execute VirtualMachinesSync: ${cloud.id} ${createNew} in ${endTime - startTime} ms"
	}

	def addMissingVirtualMachines(Cloud cloud, List plans, Map hosts, Map resourcePools, Map networks, Map osTypes, List addList, ComputeServerType defaultServerType, List blackListedNames, Map usageLists, Map tags) {
		log.debug "addMissingVirtualMachines ${cloud} ${plans?.size()} ${addList?.size()} ${defaultServerType} ${blackListedNames}"

		if (!createNew)
			return

		def metricsResult = NutanixPrismComputeUtility.listVMMetrics(apiClient, authConfig, addList?.collect{ it.metadata.uuid } )
		ServicePlan fallbackPlan = new ServicePlan(code: 'nutanix-prism-internal-custom')

		for(cloudItem in addList) {
			try {
				def doCreate = !blackListedNames?.contains(cloudItem.status.name)
				if(doCreate) {
					def vmConfig = buildVmConfig(cloudItem, resourcePools, hosts)
					vmConfig.plan = SyncUtils.findServicePlanBySizing(plans, vmConfig.maxMemory, vmConfig.maxCores, null, fallbackPlan, null, cloud.account)
					ComputeServer add = new ComputeServer(vmConfig)
					add.computeServerType = defaultServerType
					ComputeServer savedServer = morpheusContext.async.computeServer.create(add).blockingGet()
					if (!savedServer) {
						log.error "Error in creating server ${add}"
					} else {
						performPostSaveSync(savedServer, cloudItem, networks, metricsResult, tags)
					}

					if (vmConfig.powerState == ComputeServer.PowerState.on) {
						usageLists.startUsageIds << savedServer.id
					} else {
						usageLists.stopUsageIds << savedServer.id
					}
				}

			} catch(e) {
				log.error "Error in adding VM ${e}", e
			}
		}
	}

	protected updateMatchedVirtualMachines(Cloud cloud, List plans, Map hosts, Map resourcePools, Map networks, Map osTypes, List<SyncTask.UpdateItem<ComputeServer, Instance>> updateList, Map usageLists, Map tags) {
		log.debug "updateMatchedVirtualMachines: ${cloud} ${updateList?.size()}"

		ServicePlan fallbackPlan = new ServicePlan(code: 'nutanix-prism-internal-custom')
		List<ComputeServer> servers = updateList.collect { it.existingItem }

		// Gather up all the Workloads that may pertain to the servers we are syncing
		def managedServerIds = servers?.findAll{it.computeServerType?.managed }?.collect{it.id}
		Map<Long, WorkloadIdentityProjection> tmpWorkloads = morpheusContext.async.workload.list(new DataQuery().withFilter('server.id', 'in', managedServerIds)).toMap {it.serverId}.blockingGet()
		List<ComputeServer> serversToSave = []
		def metricsResult = NutanixPrismComputeUtility.listVMMetrics(apiClient, authConfig, updateList?.collect{ it.masterItem.metadata.uuid } )
		for(update in updateList) {
			try {
				ComputeServer currentServer = update.existingItem
				def cloudItem = update.masterItem
				if (currentServer.status != 'provisioning') {
					try {
						def vmConfig = buildVmConfig(cloudItem, resourcePools, hosts)

						def save = false
						def planInfoChanged = false
						if(currentServer.name != vmConfig.name) {
							currentServer.name = vmConfig.name
							save = true
						}

						if(currentServer.externalIp != vmConfig.externalIp) {
							currentServer.externalIp = vmConfig.externalIp
							currentServer.internalIp = vmConfig.externalIp
							currentServer.sshHost = vmConfig.externalIp
							save = true
						}

						if(currentServer.resourcePool?.id != vmConfig.resourcePool?.id) {
							currentServer.resourcePool = vmConfig.resourcePool
							save = true
						}

						if(currentServer.maxMemory != vmConfig.maxMemory) {
							currentServer.maxMemory = vmConfig.maxMemory
							planInfoChanged = true
							save = true
						}

						if(currentServer.maxCores != vmConfig.maxCores) {
							currentServer.maxCores = vmConfig.maxCores
							planInfoChanged = true
							save = true
						}

						if(currentServer.coresPerSocket != vmConfig.coresPerSocket) {
							currentServer.coresPerSocket = vmConfig.coresPerSocket
							planInfoChanged = true
							save = true
						}

						if(currentServer.parentServer?.id != vmConfig.parentServer?.id) {
							currentServer.parentServer = vmConfig.parentServer
							save = true
						}

						ServicePlan plan = SyncUtils.findServicePlanBySizing(plans, currentServer.maxMemory, currentServer.maxCores, null, fallbackPlan, currentServer.plan, currentServer.account)
						if(currentServer.plan?.code != plan?.code) {
							currentServer.plan = plan
							planInfoChanged = true
							save = true
						}

						if(save) {
							currentServer = saveAndGet(currentServer)
						}

						def changes = performPostSaveSync(currentServer, cloudItem, networks, metricsResult, tags)
						if(changes || save) {
							currentServer = morpheusContext.async.computeServer.get(currentServer.id).blockingGet()
							planInfoChanged = true
						}

						if(planInfoChanged && currentServer.computeServerType?.guestVm) {
							NutanixPrismSyncUtils.updateServerContainersAndInstances(currentServer, null, morpheusContext)
						}

						if(currentServer.powerState != vmConfig.powerState) {
							currentServer.powerState = vmConfig.powerState
							save = true
							if (currentServer.computeServerType?.guestVm) {
								morpheusContext.async.computeServer.updatePowerState(currentServer.id, currentServer.powerState).blockingGet()
							}
						}

						//check for restart usage records
						if (planInfoChanged ) {
							if (!usageLists.stopUsageIds.contains(currentServer.id) && !usageLists.startUsageIds.contains(currentServer.id))
								usageLists.restartUsageIds << currentServer.id
						}


						if (save) {
							serversToSave << currentServer
						}

					} catch (ex) {
						log.warn("Error Updating Virtual Machine ${currentServer?.name} - ${currentServer.externalId} - ${ex}", ex)
					}
				}
			} catch(e) {
				log.error "Error in updating server: $e", e
			}
		}

		if(serversToSave) {
			BulkSaveResult<ComputeServer> saveResult = morpheusContext.async.computeServer.bulkSave(serversToSave).blockingGet()
			serversToSave = []

			for(ComputeServer currentServer : saveResult.persistedItems) {
				Map cloudItem = updateList.find { it.existingItem.id == currentServer.id }.masterItem

				def saveRequired = performPostSaveSync(currentServer, cloudItem, networks, metricsResult, tags)

				//check for restart usage records
				if(saveRequired) {
					if (!usageLists.stopUsageIds.contains(currentServer.id) && !usageLists.startUsageIds.contains(currentServer.id)) {
						usageLists.restartUsageIds << currentServer.id
					}
					serversToSave << currentServer
				}
			}
		}

		if(serversToSave) {
			morpheusContext.async.computeServer.bulkSave(serversToSave).blockingGet()
		}
	}

	protected removeMissingVirtualMachines(Cloud cloud, List removeList, List blackListedNames) {
		log.debug "removeMissingVirtualMachines: ${cloud} ${removeList.size()}"
		for(ComputeServerIdentityProjection removeItem in removeList) {
			try {
				def doDelete = true
				if(blackListedNames?.contains(removeItem.name))
					doDelete = false
				if(doDelete) {
					log.info("remove vm: ${removeItem}")
					morpheusContext.async.computeServer.bulkRemove([removeItem]).blockingGet()
				}
			} catch(e) {
				log.error "Error removing virtual machine: ${e}", e
				log.warn("Unable to remove Server from inventory, Perhaps it is associated with an instance currently... ${removeItem.name} - ID: ${removeItem.id}")
			}
		}
	}


	private Map getAllHosts() {
		log.debug "getAllHosts: ${cloud}"
		def hostIdentitiesMap = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter {
			it.computeServerTypeCode == 'nutanix-prism-hypervisor'
		}.toMap {it.externalId }.blockingGet()
		hostIdentitiesMap
	}

	private Map getAllResourcePools() {
		log.debug "getAllResourcePools: ${cloud}"
		def resourcePoolProjectionIds = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).map{it.id}.toList().blockingGet()
		def resourcePoolsMap = morpheusContext.async.cloud.pool.listById(resourcePoolProjectionIds).toMap { it.externalId }.blockingGet()
		resourcePoolsMap
	}

	private Map getAllNetworks() {
		log.debug "getAllNetworks: ${cloud}"
		def networkProjectionsMap = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id).toMap {it.externalId }.blockingGet()
		networkProjectionsMap
	}

	private Map getAllOsTypes() {
		log.debug "getAllOsTypes: ${cloud}"
		Map osTypes = morpheusContext.async.osType.listAll().toMap {it.code}.blockingGet()
		osTypes
	}

	private List getAllServicePlans() {
		log.debug "getAllServicePlans: ${cloud}"
		def provisionType = new ProvisionType(code: 'nutanix-prism-provision-provider')
		def servicePlanProjections = morpheusContext.async.servicePlan.listIdentityProjections(provisionType).toList().blockingGet()
		def plans = morpheusContext.async.servicePlan.listById(servicePlanProjections.collect { it.id }).filter {it.active && it.deleted != true}.toList().blockingGet()
		plans
	}

	private Map getAllTags() {
		log.debug "getAllTags: ${cloud}"
		def tags = morpheusContext.async.metadataTag.listIdentityProjections(new DataQuery().withFilters([
			new DataFilter("refType", "ComputeZone"),
			new DataFilter("refId", cloud.id),
		])).toMap {it.externalId}.blockingGet()
		tags
	}

	private buildVmConfig(Map cloudItem, Map resourcePools, Map hosts) {
		CloudPool resourcePool = resourcePools[cloudItem.status?.cluster_reference?.uuid]
		def ipAddress = cloudItem.status.resources.nic_list?.getAt(0)?.ip_endpoint_list?.getAt(0)?.ip
		def vmConfig = [
				account          : cloud.account,
				externalId       : cloudItem.metadata.uuid,
				name             : cloudItem.status.name,
				externalIp       : ipAddress,
				internalIp       : ipAddress,
				sshHost          : ipAddress,
				sshUsername      : 'root',
				provision        : false,
				cloud            : cloud,
				lvmEnabled       : false,
				managed          : false,
				serverType       : 'vm',
				status           : 'provisioned',
				resourcePool     : new ComputeZonePool(id: resourcePool.id),
				uniqueId         : cloudItem.metadata.uuid,
				internalId       : cloudItem.metadata.uuid,
				powerState       : cloudItem.status.resources.power_state == 'ON' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
				maxMemory        : cloudItem.status.resources.memory_size_mib * ComputeUtility.ONE_MEGABYTE,
				maxCores         : (cloudItem.status.resources.num_vcpus_per_socket?.toLong() ?: 0) * (cloudItem.status.resources.num_sockets?.toLong() ?: 0),
				coresPerSocket   : cloudItem.status.resources.num_vcpus_per_socket?.toLong(),
				parentServer     : hosts[cloudItem.status.cluster_reference.uuid],
				osType           :'unknown',
				serverOs         : new OsType(code: 'unknown')
		]
		vmConfig
	}

	private Boolean performPostSaveSync(ComputeServer server, Map cloudItem, Map networks, metricsResult, Map tags) {
		log.debug "performPostSaveSync: ${server?.id}"
		def changes = false
		// Disks and metrics
		if(server.status != 'resizing') {
			def syncResults = NutanixPrismSyncUtils.syncVolumes(server, cloudItem.status.resources.disk_list?.findAll { it.device_properties.device_type == 'DISK' } as ArrayList, cloud, morpheusContext)
			if(!server.computeCapacityInfo) {
				server.capacityInfo = new ComputeCapacityInfo(maxCores: server.maxCores, maxMemory: server.maxMemory, maxStorage: syncResults.maxStorage)
				changes = true
			} else if(syncResults.changed) {
				server.maxStorage = syncResults.maxStorage
				server.capacityInfo.maxCores = server.maxCores
				server.capacityInfo.maxMemory = server.maxMemory
				server.capacityInfo.maxStorage = server.maxStorage
				changes = true
			}

		}

		//tags
		def vmTags = cloudItem.metadata?.categories?.collect {"${it.key}:${it.value}"}
		def existingTags = server.metadata
		def matchFunction = {existingTag, masterTag -> {
			masterTag == existingTag.externalId
		}}
		def tagSyncLists = NutanixPrismSyncUtils.buildSyncLists(existingTags, vmTags, matchFunction)
		tagSyncLists.addList?.each {
			if (tags[it]) {
				server.metadata += tags[it]
				changes = true
			}
		}
		tagSyncLists.removeList?.each {
			server.metadata.remove(it)
			changes = true
		}

		// TODO : how to get used storage?
		def metricChanges = NutanixPrismSyncUtils.updateMetrics(server, 'memory_usage_ppm', 'hypervisor_cpu_usage_ppm', metricsResult)
		if(metricChanges || changes) {
			saveAndGet(server)
		}

		// Networks
		if(server.status != 'resizing') {
			def interfaceChanges = NutanixPrismSyncUtils.syncInterfaces(server, cloudItem.status.resources.nic_list, networks, netTypes as List<ComputeServerInterfaceType>, morpheusContext)
			if (interfaceChanges) {
				changes = true
			}
		}
		return changes
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return morpheusContext.async.computeServer.get(server.id).blockingGet()
	}
}
