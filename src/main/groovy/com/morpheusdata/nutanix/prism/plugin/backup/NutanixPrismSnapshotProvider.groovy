package com.morpheusdata.nutanix.prism.plugin.backup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractMorpheusBackupTypeProvider
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.core.backup.response.BackupRestoreResponse
import com.morpheusdata.core.backup.util.BackupStatusUtility
import com.morpheusdata.core.util.DateUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.*
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import java.util.concurrent.TimeUnit

@Slf4j
class NutanixPrismSnapshotProvider extends AbstractMorpheusBackupTypeProvider {


	NutanixPrismSnapshotProvider(Plugin plugin, MorpheusContext context) {
		super(plugin, context)
	}

	@Override
	String getCode() {
		return "nutanixPrismSnapshot"
	}

	@Override
	String getName() {
		return "Nutanix Prism Central VM Snapshot"
	}

	@Override
	boolean isPlugin() {
		return true
	}

	@Override
	String getContainerType() {
		return 'single'
	}

	@Override
	Boolean getCopyToStore() {
		return false
	}

	@Override
	Boolean getDownloadEnabled() {
		return false
	}

	@Override
	Boolean getRestoreExistingEnabled() {
		return false
	}

	@Override
	Boolean getRestoreNewEnabled() {
		return true
	}

	@Override
	String getRestoreType() {
		return 'offline'
	}

	@Override
	String getRestoreNewMode() {
		return null
	}

	@Override
	Boolean getHasCopyToStore() {
		return false
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		return null
	}

	@Override
	ServiceResponse refresh(Map authConfig, BackupProvider backupProvider) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse clean(BackupProvider backupProvider, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse prepareExecuteBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse prepareBackupResult(BackupResult backupResult, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse executeBackup(Backup backup, BackupResult backupResult, Map executionConfig, Cloud cloud, ComputeServer server, Map opts) {
		log.debug("Executing backup {} with result {}", backup.id, backupResult.id)
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))

		HttpApiClient client = new HttpApiClient()

		Map authConfig = plugin.getAuthConfig(cloud)
		String vmUuid = server.externalId

		def snapshotName = "${server.name}.${server.id}.${System.currentTimeMillis()}".toString()

		if(server.serverOs?.platform != 'windows') {
			def x = getPlugin().morpheus.executeCommandOnServer(server, 'sudo rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg; sudo cp /etc/machine-id /tmp/machine-id-old; sudo rm -f /etc/machine-id; sudo touch /etc/machine-id ; sync ; sync ; sleep 5', false, server.sshUsername, server.sshPassword, null, null, null, null, true, true).blockingGet()
		}

		def snapshotResult = NutanixPrismComputeUtility.createSnapshot(client, authConfig, server.resourcePool?.externalId, vmUuid, snapshotName)
		def taskId = snapshotResult?.data?.task_uuid

		if(snapshotResult.success && taskId) {
			rtn.success = true
			// set config properties for legacy embedded compatibility.
			rtn.data.backupResult.setConfigProperty("taskId", taskId)
			rtn.data.backupResult.setConfigProperty("snapshotName", snapshotName)
			rtn.data.backupResult.internalId = taskId
			rtn.data.backupResult.backupName = snapshotName
			rtn.data.backupResult.sizeInMb = 0
			rtn.data.updates = true
		} else {
			//error
			rtn.data.backupResult.sizeInMb = 0
			rtn.data.backupResult.errorOutput = snapshotResults?.msg
			rtn.data.updates = true
		}

		return rtn
	}

	@Override
	ServiceResponse refreshBackupResult(BackupResult backupResult) {
		log.debug("refreshing backup result {}", backupResult.id)
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))
		try {

			Boolean doUpdate = false
			ComputeServer computeServer
			def cloudId = backupResult.zoneId ?: backupResult.backup?.zoneId
			if(cloudId) {
				Cloud cloud = plugin.morpheus.async.cloud.getCloudById(cloudId).blockingGet()
				HttpApiClient client = new HttpApiClient()
				Map authConfig = plugin.getAuthConfig(cloud)
				def computeServerId = backupResult.serverId ?: backupResult.backup?.computeServerId
				if(computeServerId) {
					computeServer = getPlugin().morpheus.async.computeServer.get(computeServerId).blockingGet()
					String taskId = backupResult.internalId ?: backupResult.getConfigProperty("backupRequestId")?.toString()

					log.debug("refreshBackupResult snapshot task ID: ${taskId}")
					if(taskId) {
						def taskResults = NutanixPrismComputeUtility.getTask(client, authConfig, taskId)
						if(taskResults?.data?.status == "SUCCEEDED"){
							log.debug("snapshot complete ${taskId}")
							if(taskResults.success && taskResults.data){
								def snapshotUuid = taskResults?.data?.entity_reference_list?.find { it.kind == 'snapshot'}.uuid
								def snapshotResp = NutanixPrismComputeUtility.getSnapshot(client, authConfig, computeServer?.resourcePool?.externalId, snapshotUuid)
								def snapshot = snapshotResp.data
								log.debug("Snapshot details: ${snapshot}")
								if(snapshotResp.success && snapshot && !rtn.data.backupResult.externalId) {
									rtn.data.backupResult.externalId = snapshot?.id
									rtn.data.backupResult.setConfigProperty("snapshotId", snapshot?.id)
									doUpdate = true
								}

								if(snapshotResp?.success && snapshot){
									Date createdDate = null
									if(snapshot?.created_time) {
										long milliseconds = TimeUnit.MICROSECONDS.toMillis(snapshot?.created_time)
										createdDate = new Date(milliseconds)
									}
									rtn.data.backupResult.status = BackupStatusUtility.SUCCEEDED
									rtn.data.backupResult.startDate = createdDate
									rtn.data.backupResult.endDate = DateUtility.parseDate(taskResults?.data?.completion_time)
									rtn.data.backupResult.externalId = snapshot?.uuid
									rtn.data.backupResult.setConfigProperty("snapshotId", snapshot?.uuid)
									rtn.data.backupResult.sizeInMb = 0

									def startDate = rtn.data.backupResult.startDate
									def endDate = rtn.data.backupResult.endDate
									if(startDate && endDate){
										def start = DateUtility.parseDate(startDate)
										def end = DateUtility.parseDate(endDate)
										rtn.data.backupResult.durationMillis = end.time - start.time
									}
									doUpdate = true
								}
							}
						} else if (taskResults?.data?.status == "FAILED") {
							def updatedStatus = BackupStatusUtility.FAILED
							if(rtn.data.backupResult.status != updatedStatus) {
								rtn.data.backupResult.status = updatedStatus
								doUpdate = true
							}
						}
					}
				}  else {
					rtn.data.backupResult.status = BackupStatusUtility.FAILED
					rtn.data.msg = "Associated compute server not found"
					rtn.success = false
					doUpdate = true
				}
			} else {
				rtn.data.backupResult.status = BackupStatusUtility.FAILED
				rtn.data.msg = "Associated cloud not found"
				rtn.success = false
				doUpdate = true
			}

			rtn.data.updates = doUpdate
			rtn.success = true

			if([BackupStatusUtility.FAILED, BackupStatusUtility.CANCELLED, BackupStatusUtility.SUCCEEDED].contains(rtn.data.backupResult.status)) {
				if(computeServer && computeServer.sourceImage && computeServer.sourceImage.isCloudInit && computeServer.serverOs?.platform != 'windows') {
					getPlugin().morpheus.executeCommandOnServer(computeServer, "sleep 5; sudo bash -c \"echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg\"; sudo cat /tmp/machine-id-old > /etc/machine-id ; sudo rm /tmp/machine-id-old ; sync", true, computeServer.sshUsername, computeServer.sshPassword, null, null, null, null, true, true).blockingGet()
				}
			}
		} catch(Exception e) {
			rtn.success = false
			rtn.msg = e.getMessage()
			log.error("refreshBackupResult error: ${e}", e)
		}

		return rtn
	}

	@Override
	ServiceResponse deleteBackupResult(BackupResult backupResult, Map opts) {
		log.debug("Delete backup result {}", backupResult.id)
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			def snapshotId = backupResult.externalId ?: backupResult.getConfigProperty("snapshotId")
			def cloudId = backupResult.zoneId ?: backupResult.backup?.zoneId
			def computeServerId = backupResult.serverId ?: backupResult.backup?.computeServerId
			log.info("deleteBackupResult cloudId: ${cloudId}, snapshotId: ${snapshotId}")
			if(snapshotId && cloudId && computeServerId) {
				Cloud cloud = plugin.morpheus.async.cloud.get(cloudId).blockingGet()
				HttpApiClient client = new HttpApiClient()
				Map authConfig = plugin.getAuthConfig(cloud)
				if(cloud && computeServerId) {
					def computeServer = getPlugin().morpheus.async.computeServer.get(computeServerId).blockingGet()
					def clusterId = computeServer?.resourcePool?.externalId ?: backupResult.getConfigProperty('instanceConfig')?.config?.clusterName ?: backupResult.getConfigProperty('instanceConfig')?.vmwareResourcePoolId
					def resp = NutanixPrismComputeUtility.deleteSnapshot(client, authConfig, clusterId, snapshotId)
					log.debug("Delete snapshot resp: ${resp}")
					if(resp.success) { //ignore snapshots already removed
						log.debug("Delete successful")
						rtn.success = true
					}
					log.info("deleteBackupResult result: ${rtn}")
				} else {
					rtn.success = false
					rtn.msg = "Associated cloud or server could not be found."
				}
			} else {
				// cloud or snapshot ref missing, allow delete to continue
				rtn.success = true
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
			rtn.success = false
		}

		return rtn
	}

	@Override
	ServiceResponse configureRestoreBackup(BackupResult backupResult, Map config, Map opts) {
		return ServiceResponse.success(config)
	}

	@Override
	ServiceResponse getBackupRestoreInstanceConfig(BackupResult backupResult, Instance instance, Map restoreConfig, Map opts) {
		return ServiceResponse.success(restoreConfig)
	}

	@Override
	ServiceResponse validateRestoreBackup(BackupResult backupResult, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse getRestoreOptions(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse restoreBackup(BackupRestore backupRestore, BackupResult backupResult, Backup backup, Map opts) {
		log.debug("restoreBackup ${backupResult}")
		ServiceResponse rtn = ServiceResponse.prepare(new BackupRestoreResponse(backupRestore))
		Boolean doUpdates
		try{
			def config = backupResult.getConfigMap()
			def snapshotId = backupResult.externalId ?: config.snapshotId
			if(snapshotId) {
				def sourceWorkload = plugin.morpheus.async.workload.get(opts?.containerId ?: backupResult.containerId).blockingGet()
				ComputeServer computeServer = sourceWorkload.server
				Cloud cloud = computeServer.cloud
				HttpApiClient client = new HttpApiClient()
				Map authConfig = plugin.getAuthConfig(cloud)
				def restoreResults = NutanixPrismComputeUtility.restoreSnapshot(client, authConfig, computeServer.resourcePool?.externalId, computeServer.externalId, snapshotId)
				log.info("restore results: ${restoreResults}")
				def taskId = restoreResults?.data?.task_uuid
				if(restoreResults.success){
					rtn.data.backupRestore.status = BackupStatusUtility.IN_PROGRESS
					rtn.data.backupRestore.externalId = computeServer.externalId
					rtn.data.backupRestore.externalStatusRef = taskId
					rtn.success = true
					doUpdates = true
				} else {
					rtn.data.backupRestore.status = BackupStatusUtility.FAILED
					rtn.success = false
					doUpdates = true
				}
			}

		} catch(e) {
			log.error("restoreBackup: ${e}", e)
			rtn.success = false
			rtn.msg = e.getMessage()
			rtn.data.backupRestore.status = BackupStatusUtility.FAILED
			doUpdates = true
		}

		rtn.data.updates = doUpdates
		return rtn
	}

	@Override
	ServiceResponse refreshBackupRestoreResult(BackupRestore backupRestore, BackupResult backupResult) {
		log.debug("syncBackupRestoreResult restore: ${backupRestore}")
		ServiceResponse<BackupRestoreResponse> rtn = ServiceResponse.prepare(new BackupRestoreResponse(backupRestore))
		def taskId = backupRestore.externalStatusRef
		if(taskId) {
			def sourceWorkload = plugin.morpheus.async.workload.get(backupResult.containerId).blockingGet()
			def computeServer = sourceWorkload.server
			def cloud = computeServer.cloud
			HttpApiClient client = new HttpApiClient()
			Map authConfig = plugin.getAuthConfig(cloud)
			def taskResults = NutanixPrismComputeUtility.getTask(client, authConfig, taskId)
			if(taskResults.success && taskResults.data){
				def task = taskResults.data
				if(task.status == "SUCCEEDED"){
					rtn.data.backupRestore.endDate = DateUtility.parseDate(task["completion_time"])
					rtn.data.backupRestore.status = "SUCCEEDED"
					rtn.data.backupRestore.startDate = DateUtility.parseDate(task["start_time"])
					def startDate = rtn.data.backupRestore.startDate
					def endDate = rtn.data.backupRestore.endDate
					if(startDate && endDate)
						rtn.data.backupRestore.duration = endDate.time - startDate.time
					rtn.data.updates = true
				}
			}
		}

		return rtn
	}
}
