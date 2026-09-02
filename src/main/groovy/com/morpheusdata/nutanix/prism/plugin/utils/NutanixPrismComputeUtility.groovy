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

package com.morpheusdata.nutanix.prism.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.CookieStore
import org.apache.http.client.HttpClient
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.client.utils.URIBuilder
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.conn.ssl.SSLContextBuilder
import org.apache.http.conn.ssl.TrustStrategy
import org.apache.http.conn.ssl.X509HostnameVerifier
import org.apache.http.cookie.Cookie
import org.apache.http.entity.ContentType
import org.apache.http.entity.InputStreamEntity
import org.apache.http.impl.client.BasicCookieStore
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import java.security.cert.X509Certificate

import com.morpheusdata.retry.*
import com.morpheusdata.retry.policies.*

@Slf4j
class NutanixPrismComputeUtility {

	public static enum VMM_API_VERSION {
		V4_0_A1('v4.0.a1', '4.0.a1'),
		V4_0_B1('v4.0.b1', '4.0.b1'),
		V4_0('v4.0', '4.0')
		VMM_API_VERSION(String code, String desc) {
			this.code = code
			this.desc = desc
		}
		protected String code
		protected String desc
		def getCode() { return this.code }
		def getDescription() { return this.desc}
		static VMM_API_VERSION findByCode(String code) {
			return values()?.find{it.getCode() == code}
		}
	}

	static testConnection(HttpApiClient client, Map authConfig) {
		def rtn = [success:false, invalidLogin:false]
		try {
			def listResults = listHostsV2(client, authConfig)
			if(!listResults.success) {
				rtn.invalidLogin = listResults.data?.invalidLogin
			}
			rtn.success = listResults.success
		} catch(e) {
			log.error("testConnection to ${authConfig.apiUrl}: ${e}")
		}
		return rtn
	}

	static ServiceResponse getImage(HttpApiClient client, Map authConfig, String imageId) {
		log.debug("checkImageId")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/images/${imageId}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error getting image with ID ${imageId}", null, results.data)
		}
	}

	/**
	 * Gets an image via the VMM V4 REST API (confirmed against Nutanix's published vmm v4.0.b1
	 * OpenAPI spec - Image, ImageType, UrlSource schemas). Normalizes into the same shape
	 * {@code ImagesSync}/{@code NutanixPrismProvisionProvider} already expect from V3's {@code getImage}
	 * (see {@link #normalizeImageV4}).
	 * <b>Note:</b> unlike V3 (which has an image {@code status.state} of "RUNNING"/"COMPLETE" tracking
	 * download progress), V4's {@code Image} schema has no such state field at all - image
	 * creation/download completion is tracked entirely via the Task returned by {@link #createImageV4},
	 * not by re-polling the image resource. Callers should use {@link #checkTaskReadyV4} instead of the
	 * V3 {@code waitForImageComplete} pattern of polling {@code getImage} for "COMPLETE".
	 */
	static ServiceResponse getImageV4(HttpApiClient client, Map authConfig, String imageId) {
		log.debug("getImageV4")
		ServiceResponse result = NutanixPrismV4Client.callApiV4(client, NutanixPrismV4Client.buildVmmContentV4Path(authConfig, "images/${imageId}"), authConfig)
		if (result.success) {
			result.data = normalizeImageV4(result.data)
		}
		return result
	}

	static ServiceResponse deleteImage(HttpApiClient client, Map authConfig, String imageId) {
		log.debug("deleteImage")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/images/${imageId}", authConfig.username, authConfig.password,
			new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'DELETE')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error()
		}
	}

	/**
	 * Deletes an image via the VMM V4 REST API. Like {@link #createImageV4}, this is asynchronous -
	 * the response is a {@code prism.config.TaskReference}, normalized into the same
	 * {@code status.execution_context.task_uuid} shape V3's task-based flows already expect so callers
	 * can pass the result straight to {@link #checkTaskReadyV4}.
	 */
	static ServiceResponse deleteImageV4(HttpApiClient client, Map authConfig, String imageId) {
		log.debug("deleteImageV4")
		ServiceResponse result = NutanixPrismV4Client.callApiV4(client, NutanixPrismV4Client.buildVmmContentV4Path(authConfig, "images/${imageId}"), authConfig, [:], 'DELETE')
		if (result.success) {
			result.data = [status: [execution_context: [task_uuid: result.data?.extId]]]
		}
		return result
	}

	static ServiceResponse createImage(HttpApiClient client, Map authConfig, String imageName, String imageType, String sourceUri = null, String diskUuid = null) {
		log.debug("createImage")
		def body = [
				spec: [
				        name: imageName,
						resources: [
						        image_type: imageType
						]
				],
				metadata: [
				        kind: 'image'
				]
		]
		if(sourceUri) {
			body.spec.resources.source_uri = sourceUri
		}
		if(diskUuid) {
			body.spec.resources.data_source_reference = [
			    "kind": "vm_disk",
				"uuid": diskUuid
			]
		}
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/images", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error creating image for ${imageName}", null, results.data)
		}
	}

	/**
	 * Creates an image via the VMM V4 REST API (confirmed against Nutanix's published vmm v4.0.b1
	 * OpenAPI spec). Only URL-based image creation ({@code UrlSource}) and VM-disk-clone creation
	 * ({@code VmDiskSource}) are supported - this matches the only two ways {@code createImage} is
	 * actually called in this codebase today (a Morpheus-hosted file-stream URL, or a
	 * {@code diskUuid} to clone). {@code $objectType} discriminator values follow Nutanix's documented
	 * v4 convention of {@code "{namespace}.v4.{module}.{TypeName}"} (confirmed via the schema's example
	 * value), not the internal {@code "{namespace}.v4.r0.b1.{module}.{TypeName}"} schema name.
	 * <p>
	 * <b>Critical structural difference from V3:</b> V3's {@code createImage} returns the new image's
	 * {@code metadata.uuid} synchronously, with only the *download* tracked async via
	 * {@code status.state}. V4's create is fully async - the POST only returns a
	 * {@code prism.config.TaskReference}; the new image's extId is not known until the task completes
	 * (delivered in the task's {@code entitiesAffected} list). This response is normalized into V3's
	 * {@code status.execution_context.task_uuid} shape so callers can reuse the existing
	 * "get task_uuid, then poll" pattern, but callers must switch from
	 * {@code imageResults.data.metadata.uuid} + {@code waitForImageComplete} to
	 * {@code checkTaskReadyV4(...).data.entity_reference_list.find { it.kind == 'image' }?.uuid} - there
	 * is no separate "wait for image complete" step needed since the task itself does not complete
	 * until the image is fully created (this is inferred from the API's 202/Task design and the total
	 * absence of a download-progress field on the Image resource; not independently confirmed against a
	 * live long-running image download).
	 */
	static ServiceResponse createImageV4(HttpApiClient client, Map authConfig, String imageName, String imageType, String sourceUri = null, String diskUuid = null) {
		log.debug("createImageV4")
		def body = [
				name: imageName,
				type: imageType
		]
		if (sourceUri) {
			body.source = ['$objectType': 'vmm.v4.content.UrlSource', url: sourceUri]
		} else if (diskUuid) {
			body.source = ['$objectType': 'vmm.v4.content.VmDiskSource', extId: diskUuid]
		}
		ServiceResponse result = NutanixPrismV4Client.callApiV4(client, NutanixPrismV4Client.buildVmmContentV4Path(authConfig, 'images'), authConfig, [:], 'POST', body)
		if (result.success) {
			result.data = [status: [execution_context: [task_uuid: result.data?.extId]]]
		}
		return result
	}

	private static Map normalizeImageV4(Map image) {
		def sourceUrl = image.source?.url
		return [
				metadata: [uuid: image.extId],
				status  : [
						name     : image.name,
						resources: [
								image_type                    : image.type,
								size_bytes                     : image.sizeBytes,
								retrieval_uri_list             : sourceUrl ? [sourceUrl] : [],
								source_uri                     : sourceUrl,
								current_cluster_reference_list : (image.clusterLocationExtIds ?: []).collect { [uuid: it] }
						]
				]
		]
	}

	/**
	 * V3-only direct binary upload of image bytes. Confirmed unused anywhere in this codebase today
	 * (grep found no callers) - the actual "customer uploaded image" flow instead has Morpheus serve
	 * the file via its own HTTP stream URL and passes that URL to {@code createImage}'s {@code sourceUri}
	 * (see the comment in {@code NutanixPrismProvisionProvider} explaining this). Left as V3-only and
	 * NOT migrated: Nutanix's vmm v4.0.b1 API has no binary/multipart image upload endpoint at all -
	 * image creation is exclusively URL-based ({@code UrlSource}) or disk-clone-based
	 * ({@code VmDiskSource}), confirmed by inspecting every {@code images} path in the vmm v4.0.b1
	 * OpenAPI spec. Since the existing URL-based flow already covers the plugin's real usage, this is
	 * not considered a functional regression - flagging here so it isn't silently assumed migrated.
	 */
	static ServiceResponse uploadImage(HttpApiClient client, Map authConfig, String imageExternalId, InputStream stream, Long contentLength) {
		log.debug("uploadImage: ${imageExternalId}")
		def imageStream = new BufferedInputStream(stream, 1200)
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/images/${imageExternalId}/file", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type': ContentType.APPLICATION_OCTET_STREAM.toString(), 'Connection': 'Keep-Alive'], contentType: ContentType.APPLICATION_OCTET_STREAM, body: imageStream, contentLength: contentLength, ignoreSSL: true), 'PUT')
		if(results?.success) {
			return ServiceResponse.success()
		} else {
			return ServiceResponse.error()
		}
	}

	static ServiceResponse createVm(HttpApiClient client, Map authConfig, Map runConfig) {
		log.debug("createVM")

		def resources = [
				num_sockets: runConfig.numSockets,
				memory_size_mib: runConfig.maxMemory,
				num_vcpus_per_socket: runConfig.coresPerSocket,
				disk_list: runConfig.diskList,
				nic_list: runConfig.nicList,
		]

		if(runConfig.diskList.size() > 1 || runConfig.uefi) {
			resources['boot_config'] = [boot_device: [disk_address:[adapter_type:runConfig.storageType.toUpperCase(), device_index:0]]]
		}

		if(runConfig.uefi) {
			resources['boot_config'] = resources['boot_config'] ?:[:]
			resources['boot_config']['boot_type'] = "UEFI"
			if(runConfig.secureBoot) {
				resources['machine_type'] = "Q35"
				resources['boot_config']['boot_type'] = "SECURE_BOOT"
			}
			if(runConfig.windowsCredentialGuard) {
				resources['hardware_virtualization_enabled'] = true
			}
			if(runConfig.vtpm) {
				resources['vtpm_config'] = ["vtpm_enabled": true]
			}

		}

		if(runConfig.vtpm) {
			resources['vtpm_config'] = ['vtpm_enabled': true]
		}

		if(runConfig.cloudInitUserData) {
			if(runConfig.isSysprep) {
				resources['guest_customization'] = [
					"sysprep": [
						"unattend_xml": runConfig.cloudInitUserData
					]
				]
			} else {
				resources['guest_customization'] = [
					"cloud_init": [
						"user_data": runConfig.cloudInitUserData
					],
					"is_overridable": true
				]
			}
		}

		def body = [
				spec: [
						name: runConfig.name,
						resources: resources,
						cluster_reference: runConfig.clusterReference
				],
				metadata: [
						kind: 'vm'
				]
		]

		if(runConfig.projectReference) {
			body.metadata.project_reference = runConfig.projectReference
		}

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error creating vm for ${runConfig.name}", null, results.data)
		}
	}

	static ServiceResponse cloneVm(HttpApiClient client, Map authConfig, Map runConfig, String vmUuid) {
		log.debug("cloneVm")

		def body = [
			override_spec: [
				name: runConfig.name,
				num_sockets: runConfig.numSockets,
				memory_size_mib: runConfig.maxMemory,
				num_vcpus_per_socket: runConfig.coresPerSocket,
				nic_list: runConfig.nicList
			]
		]

		if(runConfig.cloudInitUserData) {
			body['override_spec']['guest_customization'] = [
				"cloud_init": [
					"user_data": runConfig.cloudInitUserData
				],
				"is_overridable": true
			]
		}

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms/${vmUuid}/clone", authConfig.username, authConfig.password,
			new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error cloning vm for ${runConfig.name}", null, results.data)
		}
	}

	static ServiceResponse cloneSnapshot(HttpApiClient client, Map authConfig, Map runConfig, String snapshotUuid) {
		log.debug("cloneSnapshot")

		def clusterUuid = runConfig.clusterReference?.uuid

		def body = [
			spec_list: [
				[
					name: runConfig.name,
					num_vcpus: runConfig.numSockets,
					memory_mb: runConfig.maxMemory,
					num_cores_per_vcpu: runConfig.coresPerSocket,
					override_network_config: false
				]
			],
			vm_customization_config: [
			   userdata: runConfig.cloudInitUserData,
			   fresh_install: false
			]

		]


		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots/${snapshotUuid}/clone", authConfig.username, authConfig.password,
			new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error cloning snapshot vm for ${runConfig.name}", null, results.data)
		}
	}


	static ServiceResponse getTask(HttpApiClient client, Map authConfig, String uuid) {
		log.debug("getTask")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/tasks/${uuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error getting task ${uuid}", null, results.data)
		}
	}

	/**
	 * Gets a task via the Prism V4 REST API (confirmed against Nutanix's published prism v4.0.b1
	 * OpenAPI spec - Task, TaskStatus, EntityReference schemas). V4's {@code TaskStatus} enum
	 * ("QUEUED"/"RUNNING"/"CANCELING"/"SUCCEEDED"/"FAILED"/"CANCELED") already matches the terminal
	 * values ("SUCCEEDED"/"FAILED") the V3-based {@code checkTaskReady} polling loop checks for, so no
	 * transform is needed there. Normalizes the response into the same shape V3 callers expect:
	 * <ul>
	 *   <li>V4 {@code status} -&gt; V3 {@code status} (values already match)</li>
	 *   <li>V4 {@code entitiesAffected[]} ({@code {extId, rel}}, where {@code rel} is
	 *       "namespace:module[:submodule]:entityType", e.g. "vmm:ahv:vm") -&gt; V3
	 *       {@code entity_reference_list[]} ({@code {kind, uuid}})</li>
	 * </ul>
	 * <b>Not independently verified against a live task response:</b> {@code kind} is inferred as the
	 * last colon-separated segment of {@code rel} (per the schema's documented format), since no live
	 * Prism Central instance was available this session to confirm the exact {@code rel} string Nutanix
	 * returns for an image-creation task. Confirm this before relying on it for anything beyond the
	 * "find the created image's extId" use in {@code createImageV4}.
	 */
	static ServiceResponse getTaskV4(HttpApiClient client, Map authConfig, String uuid) {
		log.debug("getTaskV4")
		ServiceResponse result = NutanixPrismV4Client.callApiV4(client, NutanixPrismV4Client.buildPrismV4Path("tasks/${uuid}"), authConfig)
		if (result.success) {
			result.data = normalizeTaskV4(result.data)
		}
		return result
	}

	private static Map normalizeTaskV4(Map task) {
		return [
				status               : task.status,
				entity_reference_list: (task.entitiesAffected ?: []).collect { ref ->
					[kind: ref.rel?.tokenize(':')?.last(), uuid: ref.extId]
				}
		]
	}

	/**
	 * V4 equivalent of {@code checkTaskReady} - polls a Prism V4 task via {@link #getTaskV4} using the
	 * exact same polling contract (20s interval, 60 attempts, success/data on "SUCCEEDED",
	 * failure/data on "FAILED") so callers migrated to V4 create/delete flows can reuse the same
	 * calling pattern as the V3 task-polling code elsewhere in the provision provider.
	 */
	static checkTaskReadyV4(HttpApiClient client, Map authConfig, String taskId) {
		def rtn = [success: false]
		try {
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 20l)
				def taskDetail = getTaskV4(client, authConfig, taskId)
				log.debug("taskDetail: ${taskDetail}")
				def taskStatus = taskDetail?.data?.status
				if (taskDetail.success == true && taskStatus) {
					if (taskStatus == 'SUCCEEDED') {
						rtn.success = true
						rtn.data = taskDetail.data
						pending = false
					} else if (taskStatus == 'FAILED') {
						rtn.success = false
						rtn.data = taskDetail.data
						pending = false
					}
				}
				attempts++
				if (attempts > 60)
					pending = false
			}
		} catch (e) {
			log.error("An Exception Has Occurred: ${e.message}", e)
		}
		return rtn
	}

	static ServiceResponse getVm(HttpApiClient client, Map authConfig, String uuid) {
		log.debug("getVm")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms/${uuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error getting vm ${uuid}", null, results.data)
		}
	}

	static ServiceResponse startVm(HttpApiClient client, Map authConfig, String uuid, Map vmBody) {
		log.debug("startVm")
		if(vmBody?.spec?.resources?.power_state) {
			vmBody.spec.resources.power_state = 'ON'
		}
		Closure<Map> refreshVmBodyClosure = {
			Map vmResource = refreshVmBody(client, authConfig, uuid, vmBody)
			if(vmResource?.spec?.resources?.power_state) {
				vmResource.spec.resources.power_state = 'ON'
			}
			return vmResource
		}
		return retryableUpdateVm(client, authConfig, uuid, vmBody, null, refreshVmBodyClosure)
	}

	static ServiceResponse stopVm(HttpApiClient client, Map authConfig, String uuid, Map vmBody) {
		log.debug("stopVm")
		if(vmBody?.spec?.resources?.power_state) {
			vmBody.spec.resources.power_state = 'OFF'
		}
		Closure<Map> refreshVmBodyClosure = {
			Map vmResource = refreshVmBody(client, authConfig, uuid, vmBody)
			if(vmResource?.spec?.resources?.power_state) {
				vmResource.spec.resources.power_state = 'OFF'
			}
			return vmResource
		}
		return retryableUpdateVm(client, authConfig, uuid, vmBody, null, refreshVmBodyClosure)
	}

	static adjustVmResources(HttpApiClient client, Map authConfig, String uuid, Map updateConfig, Map vmBody) {

		if(vmBody?.spec?.resources) {
			vmBody?.spec?.resources['num_sockets'] = updateConfig.numSockets
			vmBody?.spec?.resources['memory_size_mib'] = updateConfig.maxMemory
			vmBody?.spec?.resources['num_vcpus_per_socket'] = updateConfig.coresPerSocket
		}
		return retryableUpdateVm(client, authConfig, uuid, vmBody)
	}

	static ServiceResponse updateVm(HttpApiClient client, Map authConfig, String uuid, Map vmBody) {
		vmBody?.remove('status')
		vmBody?.metadata?.remove('spec_hash')
		log.debug("updateVm")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms/${uuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: vmBody, ignoreSSL: true), 'PUT')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error updating vm ${uuid}", null, results.data)
		}
	}

	static ServiceResponse retryableUpdateVm(HttpApiClient client, Map authConfig, String uuid, Map vmBody, RetryUtility retryUtility = null, Closure<Map> refreshVmBody = null) {
		log.debug("retryableUpdateVm")
		if(!retryUtility) {
			retryUtility = getSimpleRetryUtility()
		}
		def retryClosure = { RetryUtility ru ->
			def currentAttempt = ru.getCurrentAttempt()
			def maxAttempts = ru.getMaxAttempts()
			log.debug("retryableUpdateVm attempt: ${currentAttempt}, maxAttempts: ${maxAttempts - 1}")
			if(currentAttempt > 1 && refreshVmBody) {
				//need to refresh the vmBody
				vmBody = refreshVmBody()
				log.debug("New vm body: {}", vmBody)
			}
			vmBody?.remove('status')
			vmBody?.metadata?.remove('spec_hash')
			def rtn = callApi("${authConfig.basePath}/vms/${uuid}", client, authConfig, 'PUT', ['Content-Type':'application/json'], vmBody, [:])
			if(isApiRetryRequired(rtn) && (currentAttempt < (maxAttempts - 1))) { //if reaching max attempts then just return the original results of API
				throw retryException
			}
			return rtn
		}

		RetryableFunction rf = new RetryableFunction(retryClosure, retryUtility)
		try {
			def results = retryUtility.execute(rf)
			if (results instanceof ServiceResponse) {
				if(results?.success) {
					return ServiceResponse.success(results.data)
				} else {
					return ServiceResponse.error("Error updating vm ${uuid}", null, results.data)
				}
			} else {
				return ServiceResponse.error("Unable to obtains results from retryable update VM")
			}
		} catch (RetryException e) {
			return ServiceResponse.error("Unable to obtain results from retryable update VM")
		}
	}

	static ServiceResponse destroyVm(HttpApiClient client, Map authConfig, String uuid) {
		log.debug("destroyVm")
		def results = callRetryableApi("${authConfig.basePath}/vms/${uuid}", client, authConfig, 'DELETE', ['Content-Type':'application/json'])
//		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/vms/${uuid}", authConfig.username, authConfig.password,
//				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'DELETE')
		if(results?.success) {
			return ServiceResponse.success(results.data)
		} else {
			return ServiceResponse.error("Error deleting vm ${uuid}", null, results.data)
		}
	}

	static Map getNutanixSession(Map authConfig) {
		URIBuilder uriBuilder = new URIBuilder(authConfig.apiUrl)
		uriBuilder.setPath("api/nutanix/v3/users/info")

		HttpRequestBase request
		request = new HttpGet(uriBuilder.build())
		def cookies = []
		def sessionCookie = [:]

		def outboundClient
		def rtn = [success: false]
		try {
			def outboundSslBuilder = new SSLContextBuilder()
			outboundSslBuilder.loadTrustMaterial(null, new TrustStrategy() {
				@Override
				boolean isTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
					return true
				}
			})
			def outboundSocketFactory = new SSLConnectionSocketFactory(outboundSslBuilder.build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
			def clientBuilder = HttpClients.custom().setSSLSocketFactory(outboundSocketFactory)
			clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
				boolean verify(String host, SSLSession sess) { return true }

				void verify(String host, SSLSocket ssl) {}

				void verify(String host, String[] cns, String[] subjectAlts) {}

				void verify(String host, X509Certificate cert) {}
			})

			String creds = authConfig.username + ":" + authConfig.password
			String credHeader = "Basic " + Base64.getEncoder().encodeToString(creds.getBytes())

			outboundClient = clientBuilder.build()
			request.addHeader("Authorization", credHeader)
			request.addHeader('Accept','text/html')


			def responseBody = outboundClient.execute(request)
			if(responseBody.statusLine.statusCode < 400) {
				responseBody.getHeaders('Set-Cookie').each {
					String cookie = it.value.split(';')[0]
					cookies.add(cookie)
					if(cookie.startsWith("NTNX_IGW_SESSION")) {
						sessionCookie.type = "NTNX_IGW_SESSION"
						sessionCookie.cookieValue = cookie
					} else if(cookie.startsWith("NTNX_IAM_SESSION")) {
						sessionCookie.type = "NTNX_IAM_SESSION"
						sessionCookie.cookieValue = cookie
					}
				}
				if(sessionCookie) {
					return sessionCookie
				}
			} else {
				rtn.success = false
			}
		} catch(e) {
			log.error("getNutanixSession error : ${e}", e)
		} finally {
			outboundClient.close()
		}
		return null
	}

	static ServiceResponse getVMConsoleUrl(Map authConfig, String vmUuid, String clusterUuid) {


		Map nutanixSession = getNutanixSession(authConfig)
		def nutanixSessionCookie = ""
		def socketURI = new URIBuilder(authConfig.apiUrl)
		socketURI.setScheme("wss")
		socketURI.setPath("/vnc/vm/${vmUuid}/proxy")
		socketURI.setParameter("proxyClusterUuid", clusterUuid)
		if(nutanixSession?.type == "NTNX_IGW_SESSION") {
			nutanixSessionCookie = nutanixSession.cookieValue
		} else if(nutanixSession?.type == "NTNX_IAM_SESSION") {
			nutanixSessionCookie = nutanixSession.cookieValue
			return ServiceResponse.success([url: socketURI.build().toString(), headers: ['Cookie':nutanixSessionCookie]])
		}


		URIBuilder uriBuilder = new URIBuilder(authConfig.apiUrl)
		uriBuilder.setPath("PrismGateway/j_spring_security_check")

		HttpRequestBase request
		request = new HttpPost(uriBuilder.build())
		def cookies = []
		def sessionCookie

		def outboundClient
		def rtn = [success: false]
		try {
			def outboundSslBuilder = new SSLContextBuilder()
			outboundSslBuilder.loadTrustMaterial(null, new TrustStrategy() {
				@Override
				boolean isTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
					return true
				}
			})
			def outboundSocketFactory = new SSLConnectionSocketFactory(outboundSslBuilder.build(), SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
			def clientBuilder = HttpClients.custom().setSSLSocketFactory(outboundSocketFactory)
			clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
				boolean verify(String host, SSLSession sess) { return true }

				void verify(String host, SSLSocket ssl) {}

				void verify(String host, String[] cns, String[] subjectAlts) {}

				void verify(String host, X509Certificate cert) {}
			})

			outboundClient = clientBuilder.build()
			HttpEntityEnclosingRequestBase postRequest = (HttpEntityEnclosingRequestBase)request
			def formEntity = new UrlEncodedFormEntity([new BasicNameValuePair('j_username',authConfig.username), new BasicNameValuePair('j_password', authConfig.password)])
			postRequest.setEntity(formEntity)
			postRequest.addHeader('Accept','text/html')


			def responseBody = outboundClient.execute(postRequest)
			if(responseBody.statusLine.statusCode < 400) {
				responseBody.getHeaders('Set-Cookie').each {
					String cookie = it.value.split(';')[0]
					cookies.add(cookie)
					if(cookie.startsWith("JSESSIONID")) {
						sessionCookie = cookie
					}
				}
				if(sessionCookie) {
					return ServiceResponse.success([url: socketURI.build().toString(), headers: ['Cookie':sessionCookie + ";" + nutanixSessionCookie]])
				}
			} else {
				rtn.success = false
			}
		} catch(e) {
			log.error("getVmConsoleError: ${e}", e)
		} finally {
			outboundClient.close()
		}
		return ServiceResponse.error("Error getting console for vm ${vmUuid}", null,null )
	}

	static ServiceResponse listSnapshots(HttpApiClient client, Map authConfig, String clusterUuid) {
		log.debug("listSnapshots")

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], ignoreSSL: true), 'GET')

		if(results?.success) {
			return ServiceResponse.success(results?.data?.entities)
		} else {
			return ServiceResponse.error("Error listing snapshots for cluster ${clusterUuid}", null, results.data)
		}
	}


	static ServiceResponse getSnapshot(HttpApiClient client, Map authConfig, String clusterUuid, String snapshotUuid) {
		log.debug("getSnapshot")

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots/${snapshotUuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], ignoreSSL: true), 'GET')

		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error getting snapshot ${snapshotUuid}", null, results.data)
		}
	}


	static ServiceResponse createSnapshot(HttpApiClient client, Map authConfig, String clusterUuid, String vmUuid, String snapshotName) {
		log.debug("createSnapshot")

		def body = [snapshot_specs:[[vm_uuid:vmUuid, snapshot_name:snapshotName]]]

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], body: body, ignoreSSL: true), 'POST')

		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error creating snapshot for vm ${vmUuid}", null, results.data)
		}
	}

	static ServiceResponse deleteSnapshot(HttpApiClient client, Map authConfig, String clusterUuid, String snapshotUuid) {
		log.debug("deleteSnapshot")

		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/snapshots/${snapshotUuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], ignoreSSL: true), 'DELETE')
		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error deleting snapshot ${snapshotUuid}", null, results.data)
		}
	}

	static ServiceResponse restoreSnapshot(HttpApiClient client, Map authConfig, String clusterUuid, String vmUuid, String snapshotUuid) {
		log.debug("restoreSnapshot")
		def body = [restore_network_configuration: true, snapshot_uuid: snapshotUuid, uuid: vmUuid]
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/vms/${vmUuid}/restore", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: [proxyClusterUuid:clusterUuid], body: body, ignoreSSL: true), 'POST')
		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error restoring snapshot ${snapshotUuid}", null, results.data)
		}
	}

	static ServiceResponse listTemplates(HttpApiClient client, Map authConfig) {
		VMM_API_VERSION apiVersion = authConfig.vmmApiVersion
		def results = [success: false]
		if (apiVersion == VMM_API_VERSION.V4_0_A1) {
			results = client.callJsonApi(authConfig.apiUrl, "api/vmm/" + apiVersion.getCode() + "/templates", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, queryParams: ["\$expand":"vmSpec"], ignoreSSL: true), 'GET')
		} else {
			results = client.callJsonApi(authConfig.apiUrl, "api/vmm/" + apiVersion.getCode() + "/content/templates", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		}
		if(results?.success) {
			results.data?.data?.each { template ->
				if (template && template?.templateVersionSpec) {
					//have to fetch full template to get vmSpec with storage containers and disk info
					if (apiVersion == VMM_API_VERSION.V4_0_A1) {
						template.templateVersionSpec?.vmSpec = normalizeVmSpec(template.templateVersionSpec?.vmSpec, apiVersion)
					} else  {
						def getResponse = getTemplate(client, authConfig, template.extId)
						if(getResponse.success) {
							template.templateVersionSpec?.vmSpec = getResponse.data?.data?.templateVersionSpec?.vmSpec
						}
					}
				}
			}
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error listing templates", null, results.data)
		}
	}

	static normalizeVmSpec(Object vmSpec, VMM_API_VERSION vmmApiVersion) {
		def rtn = [:]
		def diskList = []
		if(vmSpec) {
			if (vmmApiVersion == VMM_API_VERSION.V4_0_A1) {
				if (vmSpec instanceof String) {
					rtn.rawSpec = new JsonSlurper().parseText(vmSpec)
					diskList = rtn.rawSpec?.spec?.resources?.disk_list
					rtn.disk_list = diskList
				}
			} else if (vmmApiVersion == VMM_API_VERSION.V4_0_B1 || vmmApiVersion == VMM_API_VERSION.V4_0) {
				if (vmSpec instanceof Map) {
					//ugly, but normalise to v3 style for now
					rtn.rawSpec = vmSpec
					diskList = rtn.rawSpec?.disks
					diskList = diskList.collect { disk ->
						def diskData = disk.backingInfo
						def size = diskData?.diskSizeBytes
						//no CDs listed under disk
						def type = "DISK"
						def busType = disk?.diskAddress?.busType
						def deviceIndex = disk?.diskAddress?.index
						def storageContainer = diskData?.storageContainer?.extId
						def uuid = disk?.extId //todo:: Fix volumes re-creating on every cloud sync. documented property but it does not exist in my testing. Perhaps a 4_0_B1 bug
						return [
							device_properties: [
								device_type: type,
								disk_address: [
									device_bus: busType,
									device_index: deviceIndex,
									adapter_type: busType,
								]
							],
							disk_size_bytes: size,
							storage_config: [
								storage_container_reference: [
									uuid: storageContainer
								]
							],
							uuid: uuid
						]
					}
					rtn.disk_list = diskList
				}
			}

		}
		return rtn
	}

	static ServiceResponse getTemplate(HttpApiClient client, Map authConfig, String templateUuid) {
		VMM_API_VERSION apiVersion = authConfig.vmmApiVersion
		def results = [success: false]
		if (apiVersion == VMM_API_VERSION.V4_0_A1) {
			results = client.callJsonApi(authConfig.apiUrl, "api/vmm/" + apiVersion.getCode() + "/templates/${templateUuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		} else {
			results = client.callJsonApi(authConfig.apiUrl, "api/vmm/" + apiVersion.getCode() + "/content/templates/${templateUuid}", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		}
		if(results?.success) {
			def template = results.data?.data
			if (template && template?.templateVersionSpec) {
				template.templateVersionSpec?.vmSpec = normalizeVmSpec(template.templateVersionSpec?.vmSpec, apiVersion)
			}
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error getting template ${templateUuid}", null, results.data)
		}
	}

	static ServiceResponse createCategoryKey(HttpApiClient client, Map authConfig, String keyName) {
		def body = [name: keyName]
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/categories/${keyName}", authConfig.username, authConfig.password,
			new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'PUT')
		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error creating category key ${keyName}", null, results.data)
		}
	}

	static ServiceResponse createCategoryValue(HttpApiClient client, Map authConfig, String keyName, String valueName) {
		def body = [value: valueName]
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/categories/${keyName}/${valueName}", authConfig.username, authConfig.password,
			new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'PUT')
		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error creating category value ${valueName} in key, ${keyName}", null, results.data)
		}
	}

	static ServiceResponse createVmFromTemplate(HttpApiClient client, Map authConfig, Map runConfig) {
		def templateUuid = runConfig.imageExternalId
		def headers = [
			'Content-Type':'application/json',
			'NTNX-Request-Id': UUID.randomUUID().toString()
		]
		def body = [
			"numberOfVms": 1,
			"clusterReference": runConfig.clusterReference.uuid,
			"startIndex": 0,
			"vmName": runConfig.name,
			"overrideVmConfigMap": [
				"0": [
					name: runConfig.name,
					numSockets: runConfig.numSockets,
					memorySizeBytes: runConfig.maxMemory * ComputeUtility.ONE_MEGABYTE,
					numCoresPerSocket: runConfig.coresPerSocket,
					nics: convertNicListTov4(runConfig.nicList)
				]
			]
		]
		if(runConfig.cloudInitUserData) {
			if(runConfig.isSysprep) {
				body["overrideVmConfigMap"]["0"]['guestCustomization'] = [
					"config": [
						"\$objectType": "vmm.v4.ahv.config.Sysprep",
						"sysprepScript": [
							"\$objectType":"vmm.v4.ahv.config.Unattendxml",
							value: runConfig.cloudInitUserData
						]
					],


				]
			} else {
				body['overrideVmConfigMap']["0"]['guestCustomization'] = [
					"config": [
						"\$objectType": "vmm.v4.ahv.config.CloudInit",
						"cloudInitScript": [
							"\$objectType":"vmm.v4.ahv.config.Userdata",
							value: runConfig.cloudInitUserData
						]
					],
				]
			}

		}
		VMM_API_VERSION apiVersion = authConfig.vmmApiVersion
		def results = [success: false]
		if (apiVersion == VMM_API_VERSION.V4_0_A1) {
			results = client.callJsonApi(authConfig.apiUrl, "api/vmm/" + apiVersion.getCode() + "/templates/${templateUuid}/\$actions/deploy", authConfig.username, authConfig.password,
				new HttpApiClient.RequestOptions(headers: headers, contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'POST')
		} else {
			results = client.callJsonApi(authConfig.apiUrl, "api/vmm/" + apiVersion.getCode() + "/content/templates/${templateUuid}/\$actions/deploy", authConfig.username, authConfig.password,
							new HttpApiClient.RequestOptions(headers: headers, contentType: ContentType.APPLICATION_JSON, body: body, ignoreSSL: true), 'POST')
		}
		if(results?.success) {
			return ServiceResponse.success(results?.data)
		} else {
			return ServiceResponse.error("Error creating vm from template ${results}", null, results.data)
		}
	}

	static ServiceResponse getProject(HttpApiClient client, Map authConfig, String uuid) {
		log.debug("getVm")
		def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/projects/${uuid}", authConfig.username, authConfig.password,
			new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'GET')
		if(results?.success) {
			return ServiceResponse.success(results.data.status.resources)
		} else {
			return ServiceResponse.error("Error getting project ${uuid}", null, results.data)
		}
	}

	static ServiceResponse listNetworks(HttpApiClient client, Map authConfig) {
		log.debug("listNetworks")
		return callListApi(client, 'subnet', 'subnets/list', authConfig)
	}

	/**
	 * Lists subnets via the Networking V4 REST API (confirmed against Nutanix's published networking
	 * v4.0.b1 OpenAPI spec - Subnet, IPConfig, IPv4Config schemas). Normalizes each V4 Subnet entity into
	 * the same shape the V3 {@code listNetworks}/{@code NetworksSync} consumers already expect:
	 * <ul>
	 *   <li>V4 {@code extId} -&gt; V3 {@code metadata.uuid}</li>
	 *   <li>V4 {@code name} -&gt; V3 {@code status.name}</li>
	 *   <li>V4 {@code clusterReference} (plain extId string) -&gt; V3 {@code status.cluster_reference.uuid}</li>
	 *   <li>V4 {@code subnetType} (enum "OVERLAY"/"VLAN") -&gt; V3 {@code status.resources.subnet_type} (values already match)</li>
	 *   <li>V4 {@code ipConfig[0].ipv4.ipSubnet} -&gt; V3 {@code status.resources.ip_config.subnet_ip} (only used as a
	 *       truthy "is this subnet managed" check by the caller, so passing the raw sub-object through is sufficient)</li>
	 *   <li>V4 {@code vpcReference} (plain extId string) -&gt; V3 {@code spec.resources.vpc_reference.uuid}</li>
	 * </ul>
	 */
	static ServiceResponse listNetworksV4(HttpApiClient client, Map authConfig) {
		log.debug("listNetworksV4")
		ServiceResponse listResult = NutanixPrismV4Client.callListApiV4(client, NutanixPrismV4Client.buildNetworkingV4Path('subnets'), authConfig)
		if (listResult.success) {
			listResult.data = listResult.data?.collect { subnet -> normalizeSubnetV4(subnet) }
		}
		return listResult
	}

	private static Map normalizeSubnetV4(Map subnet) {
		def ipv4Config = subnet.ipConfig?.find { it.ipv4 }?.ipv4
		return [
				metadata: [uuid: subnet.extId],
				status  : [
						name     : subnet.name,
						cluster_reference: [uuid: subnet.clusterReference],
						resources: [
								subnet_type: subnet.subnetType,
								ip_config  : [subnet_ip: ipv4Config?.ipSubnet]
						]
				],
				spec    : [
						resources: [
								vpc_reference: [uuid: subnet.vpcReference]
						]
				]
		]
	}

	static ServiceResponse listImages(HttpApiClient client, Map authConfig) {
		log.debug("listImages")
		return callListApi(client, 'image', 'images/list', authConfig)
	}

	/**
	 * Lists images via the VMM V4 REST API (confirmed against Nutanix's published vmm v4.0.b1 OpenAPI
	 * spec - Image, ImageType, UrlSource/VmDiskSource schemas). Normalizes each V4 Image entity into
	 * the same shape the V3 {@code listImages}/{@code ImagesSync} consumers already expect - see
	 * {@link #normalizeImageV4}.
	 */
	static ServiceResponse listImagesV4(HttpApiClient client, Map authConfig) {
		log.debug("listImagesV4")
		ServiceResponse listResult = NutanixPrismV4Client.callListApiV4(client, NutanixPrismV4Client.buildVmmContentV4Path(authConfig, 'images'), authConfig)
		if (listResult.success) {
			listResult.data = listResult.data?.collect { image -> normalizeImageV4(image) }
		}
		return listResult
	}

	static ServiceResponse listDisksV2(HttpApiClient client, Map authConfig) {
		log.debug("listDisksV2")
		return callListApiV2(client, 'disks', authConfig)
	}

	static ServiceResponse listDatastores(HttpApiClient client, Map authConfig) {
		log.debug("listDatastores")
		def groupMemberAttributes = ['container_name','serial','storage.capacity_bytes','cluster','storage.free_bytes','state','message','reason']
		return callGroupApi(client, 'storage_container', 'serial', groupMemberAttributes, authConfig)
	}

	static ServiceResponse listCategories(HttpApiClient client, Map authConfig) {
		log.debug("listCategories")
		return callListApi(client, 'category', 'categories/list', authConfig)
	}

	static ServiceResponse listCategoryValues(HttpApiClient client, Map authConfig, String categoryName) {
		log.debug("listCategoryValues")
		return callListApi(client, 'category', "categories/${categoryName}/list", authConfig)
	}

	/**
	 * Lists categories via the Prism Central V4 REST API. Unlike the V3 API (separate "list keys" and
	 * "list values for key" calls), V4 categories are a flat resource where each entry already combines
	 * the key and value (e.g. {@code [extId: ..., key: 'Environment', value: 'Production', type: 'USER']}).
	 * Callers should read {@code key}/{@code value} directly instead of making a follow-up call per key.
	 */
	static ServiceResponse listCategoriesV4(HttpApiClient client, Map authConfig) {
		log.debug("listCategoriesV4")
		return NutanixPrismV4Client.callListApiV4(client, NutanixPrismV4Client.buildPrismV4Path('categories'), authConfig)
	}

	static ServiceResponse listClusters(HttpApiClient client, Map authConfig) {
		log.debug("listClusters")
		return callListApi(client, 'cluster', 'clusters/list', authConfig)
	}

	/**
	 * Lists clusters via the Prism Central V4 REST API (clustermgmt namespace - confirmed against
	 * Nutanix's published clustermgmt v4.0.b1 OpenAPI spec). Normalizes each V4 Cluster entity into
	 * the same shape the V3 {@code listClusters}/{@code ClustersSync} consumers already expect
	 * (metadata.uuid, status.name, status.resources.config.service_list, status.resources.config.software_map)
	 * so callers do not need to change:
	 * <ul>
	 *   <li>V4 {@code extId} -&gt; V3 {@code metadata.uuid}</li>
	 *   <li>V4 {@code name} -&gt; V3 {@code status.name}</li>
	 *   <li>V4 {@code config.clusterFunction} (array of strings, e.g. "AOS") -&gt; V3 {@code status.resources.config.service_list}</li>
	 *   <li>V4 {@code config.clusterSoftwareMap} (array of {@code {softwareType, version}}) -&gt; V3 {@code status.resources.config.software_map} (map keyed by softwareType, e.g. "NOS")</li>
	 * </ul>
	 * Note: V4 has no "AOS" software type - the equivalent key is "NOS" (confirmed via the
	 * clustermgmt SoftwareTypeRef enum: NOS, NCC, PRISM_CENTRAL).
	 */
	static ServiceResponse listClustersV4(HttpApiClient client, Map authConfig) {
		log.debug("listClustersV4")
		ServiceResponse listResult = NutanixPrismV4Client.callListApiV4(client, NutanixPrismV4Client.buildClusterMgmtV4Path('clusters'), authConfig)
		if (listResult.success) {
			listResult.data = listResult.data?.collect { cluster -> normalizeClusterV4(cluster) }
		}
		return listResult
	}

	private static Map normalizeClusterV4(Map cluster) {
		def softwareMap = (cluster.config?.clusterSoftwareMap ?: []).collectEntries { sw ->
			[(sw.softwareType): [version: sw.version]]
		}
		return [
				metadata: [uuid: cluster.extId],
				status  : [
						name     : cluster.name,
						resources: [
								config: [
										service_list: cluster.config?.clusterFunction ?: [],
										software_map: softwareMap
								]
						]
				]
		]
	}

	static ServiceResponse listVPCs(HttpApiClient client, Map authConfig) {
		log.debug("listVPCs")
		return callListApi(client, 'vpc', 'vpcs/list', authConfig)
	}

	/**
	 * Lists VPCs via the Networking V4 REST API (confirmed against Nutanix's published networking v4.0.b1
	 * OpenAPI spec - Vpc schema). Normalizes each V4 Vpc entity into the same shape the V3
	 * {@code listVPCs}/{@code VirtualPrivateCloudSync} consumers already expect:
	 * <ul>
	 *   <li>V4 {@code name} -&gt; V3 {@code spec.name}</li>
	 *   <li>V4 {@code extId} -&gt; V3 {@code metadata.uuid}</li>
	 * </ul>
	 */
	static ServiceResponse listVPCsV4(HttpApiClient client, Map authConfig) {
		log.debug("listVPCsV4")
		ServiceResponse listResult = NutanixPrismV4Client.callListApiV4(client, NutanixPrismV4Client.buildNetworkingV4Path('vpcs'), authConfig)
		if (listResult.success) {
			listResult.data = listResult.data?.collect { vpc -> [spec: [name: vpc.name], metadata: [uuid: vpc.extId]] }
		}
		return listResult
	}

	static ServiceResponse listProjects(HttpApiClient client, Map authConfig) {
		log.debug("listProjects")
		return callListApi(client, 'project', 'projects/list', authConfig)
	}

	static ServiceResponse listHostsV2(HttpApiClient client, Map authConfig) {
		log.debug("listHostsV2")
		return callListApiV2(client, 'hosts', authConfig)
	}

	/**
	 * Lists hosts via the Prism Central V4 REST API (clustermgmt namespace - confirmed against
	 * Nutanix's published clustermgmt v4.0.b1 OpenAPI spec) and normalizes each entity into the
	 * same flat shape the V2 {@code listHostsV2}/{@code HostsSync} consumers already expect
	 * (uuid, cluster_uuid, hypervisor_type, name, hypervisor_address, num_cpu_cores,
	 * memory_capacity_in_bytes, stats.hypervisor_cpu_usage_ppm, stats.hypervisor_memory_usage_ppm):
	 * <ul>
	 *   <li>V4 {@code extId} -&gt; V2 {@code uuid}</li>
	 *   <li>V4 {@code cluster.uuid} -&gt; V2 {@code cluster_uuid}</li>
	 *   <li>V4 {@code hypervisor.type} enum (AHV/ESX/HYPERV/XEN) -&gt; V2 {@code hypervisor_type} string
	 *       ("kKvm"/"kVCenter") so existing switch-on-string logic in HostsSync keeps working</li>
	 *   <li>V4 {@code hostName} -&gt; V2 {@code name}</li>
	 *   <li>V4 {@code hypervisor.externalAddress} (ipv4/ipv6 object) -&gt; V2 {@code hypervisor_address} (string)</li>
	 *   <li>V4 {@code numberOfCpuCores} -&gt; V2 {@code num_cpu_cores}</li>
	 *   <li>V4 {@code memorySizeBytes} -&gt; V2 {@code memory_capacity_in_bytes}</li>
	 * </ul>
	 * V4 has no stats fields on the host config resource itself - CPU/memory usage requires a
	 * separate per-host call to the "stats" sub-namespace (see {@link #getHostStatsV4}), fetched
	 * here with {@code $statType=LAST} to get the current point-in-time value (matching V2's
	 * "current stats" semantics).
	 */
	static ServiceResponse listHostsV4(HttpApiClient client, Map authConfig) {
		log.debug("listHostsV4")
		ServiceResponse listResult = NutanixPrismV4Client.callListApiV4(client, NutanixPrismV4Client.buildClusterMgmtV4Path('hosts'), authConfig)
		if (listResult.success) {
			listResult.data = listResult.data?.collect { host -> normalizeHostV4(client, authConfig, host) }
		}
		return listResult
	}

	private static final Map<String, String> HYPERVISOR_TYPE_V4_TO_V2 = [AHV: 'kKvm', ESX: 'kVCenter']

	private static Map normalizeHostV4(HttpApiClient client, Map authConfig, Map host) {
		def clusterUuid = host.cluster?.uuid
		def hostExtId = host.extId
		def hypervisorTypeV4 = host.hypervisor?.type?.toString()
		def stats = [:]
		if(clusterUuid && hostExtId) {
			ServiceResponse statsResult = getHostStatsV4(client, authConfig, clusterUuid, hostExtId)
			if(statsResult.success) {
				stats.hypervisor_cpu_usage_ppm = latestStatValue(statsResult.data?.hypervisorCpuUsagePpm)
				stats.hypervisor_memory_usage_ppm = latestStatValue(statsResult.data?.aggregateHypervisorMemoryUsagePpm)
			} else {
				log.warn "Error getting host stats for ${hostExtId}: ${statsResult.msg}"
			}
		}
		return [
				uuid                    : hostExtId,
				cluster_uuid            : clusterUuid,
				hypervisor_type         : HYPERVISOR_TYPE_V4_TO_V2[hypervisorTypeV4] ?: hypervisorTypeV4,
				name                    : host.hostName,
				hypervisor_address      : host.hypervisor?.externalAddress?.ipv4?.value ?: host.hypervisor?.externalAddress?.ipv6?.value,
				num_cpu_cores           : host.numberOfCpuCores,
				memory_capacity_in_bytes: host.memorySizeBytes,
				stats                   : stats
		]
	}

	private static Long latestStatValue(List timeValuePairs) {
		return timeValuePairs ? (timeValuePairs.last().value as Long) : null
	}

	static ServiceResponse getHostStatsV4(HttpApiClient client, Map authConfig, String clusterExtId, String hostExtId) {
		log.debug("getHostStatsV4: cluster=${clusterExtId} host=${hostExtId}")
		return NutanixPrismV4Client.callApiV4(client, NutanixPrismV4Client.buildClusterMgmtStatsV4Path("clusters/${clusterExtId}/hosts/${hostExtId}"), authConfig, ['$statType': 'LAST'])
	}

	static ServiceResponse listVMs(HttpApiClient client, Map authConfig) {
		log.debug("listVMs")
		return callListApi(client, 'vm', 'vms/list', authConfig)
	}

	/**
	 * Lists VMs via the VMM V4 REST API (confirmed against Nutanix's published vmm v4.0.b1 OpenAPI spec -
	 * Vm, Nic, Disk, Ipv4Config, DiskAddress, VmDisk schemas) and normalizes each entity into the same
	 * shape the V3 {@code listVMs}/{@code VirtualMachinesSync} consumers already expect:
	 * <ul>
	 *   <li>V4 {@code extId} -&gt; V3 {@code metadata.uuid}</li>
	 *   <li>V4 {@code name} -&gt; V3 {@code status.name}</li>
	 *   <li>V4 {@code cluster.extId} -&gt; V3 {@code status.cluster_reference.uuid}</li>
	 *   <li>V4 {@code powerState} ("ON"/"OFF") -&gt; V3 {@code status.resources.power_state} (values already match)</li>
	 *   <li>V4 {@code memorySizeBytes} -&gt; V3 {@code status.resources.memory_size_mib} (bytes -&gt; MiB)</li>
	 *   <li>V4 {@code numCoresPerSocket}/{@code numSockets} -&gt; V3 {@code status.resources.num_vcpus_per_socket}/{@code num_sockets}</li>
	 *   <li>V4 {@code nics[]} (extId, backingInfo.macAddress, networkInfo.nicType, networkInfo.subnet.extId,
	 *       networkInfo.ipv4Config.ipAddress.value) -&gt; V3 {@code status.resources.nic_list[]} (uuid, mac_address,
	 *       nic_type, subnet_reference.uuid, ip_endpoint_list[0].ip)</li>
	 *   <li>V4 {@code disks[]} (extId, diskAddress.index, backingInfo.diskSizeBytes) -&gt; V3
	 *       {@code status.resources.disk_list[]} (uuid, device_properties.disk_address.device_index,
	 *       disk_size_bytes, device_properties.device_type hardcoded to "DISK" since V4 already separates
	 *       cdRoms into their own array)</li>
	 * </ul>
	 * <b>Known gap:</b> V4 {@code categories} is a list of category *references* (just {@code extId}), not
	 * {@code {key, value}} pairs like V3 {@code metadata.categories}. Resolving extId -&gt; key/value requires
	 * a lookup against {@link #listCategoriesV4} output that the caller does not currently provide, so
	 * {@code metadata.categories} is normalized to an empty list for now - VM tag sync will not pick up
	 * category tags until this is wired up (tracked as a follow-up, not silently "working").
	 */
	static ServiceResponse listVMsV4(HttpApiClient client, Map authConfig) {
		log.debug("listVMsV4")
		ServiceResponse listResult = NutanixPrismV4Client.callListApiV4(client, NutanixPrismV4Client.buildVmmV4Path(authConfig, 'vms'), authConfig)
		if (listResult.success) {
			listResult.data = listResult.data?.collect { vm -> normalizeVmV4(vm) }
		}
		return listResult
	}

	private static Map normalizeVmV4(Map vm) {
		def nicList = (vm.nics ?: []).collect { nic ->
			def ip = nic.networkInfo?.ipv4Config?.ipAddress?.value
			[
					uuid            : nic.extId,
					subnet_reference: [uuid: nic.networkInfo?.subnet?.extId],
					ip_endpoint_list: ip ? [[ip: ip]] : [],
					mac_address     : nic.backingInfo?.macAddress,
					nic_type        : nic.networkInfo?.nicType
			]
		}
		def diskList = (vm.disks ?: []).collect { disk ->
			[
					uuid              : disk.extId,
					disk_size_bytes   : disk.backingInfo?.diskSizeBytes,
					device_properties: [
							device_type : 'DISK',
							disk_address: [device_index: disk.diskAddress?.index]
					]
			]
		}
		return [
				metadata: [
						uuid              : vm.extId,
						categories        : [], // see listVMsV4 doc - extId->key/value resolution not yet wired up
						project_reference : null
				],
				status  : [
						name     : vm.name,
						cluster_reference: [uuid: vm.cluster?.extId],
						resources: [
								power_state          : vm.powerState,
								memory_size_mib      : vm.memorySizeBytes != null ? (vm.memorySizeBytes / (1024 * 1024)) as Long : null,
								num_vcpus_per_socket : vm.numCoresPerSocket,
								num_sockets          : vm.numSockets,
								nic_list             : nicList,
								disk_list            : diskList
						]
				]
		]
	}

	static ServiceResponse listHostMetrics(HttpApiClient client, Map authConfig, List<String> hostUUIDs) {
		log.debug("listHostMetrics")
		def groupMemberAttributes = ['hypervisor_memory_usage_ppm', 'hypervisor_cpu_usage_ppm']
		def appendToBody = [
				entity_ids: hostUUIDs
		]
		return callGroupApi(client, 'host', 'hypervisor_memory_usage_ppm', groupMemberAttributes, authConfig, appendToBody)
	}

	static ServiceResponse listVMMetrics(HttpApiClient client, Map authConfig, List<String> vmUUIDs) {
		log.debug("listVMMetrics")
		def groupMemberAttributes = ['memory_usage_ppm', 'hypervisor_cpu_usage_ppm', 'controller_user_bytes']
		def appendToBody = [
				entity_ids: vmUUIDs
		]
		return callGroupApi(client, 'mh_vm', 'memory_usage_ppm', groupMemberAttributes, authConfig, appendToBody)
	}

	static getGroupEntityValue(List groupData, attributeName) {
		def values = groupData.find { it.name == attributeName }?.values
		if(values?.size() > 0 ) {
			return values.getAt(0).values?.getAt(0)
		}
		null
	}

	static getDiskName(Map diskData) {
		String fullName = diskData.mount_path ?: diskData.disk_uuid
		def lastSlash = fullName.lastIndexOf('/')
		if(lastSlash > 0) {
			return fullName.substring(lastSlash + 1)
		} else {
			return fullName
		}
	}

	static ServiceResponse cloudInitViaCD(HttpApiClient client, Map authConfig, String vmUuid, String imageUuid, Map vmBody) {
		log.debug("cloudInitViaCD")
		def cdromDisk = vmBody?.spec?.resources?.disk_list?.find { it.device_properties?.device_type == 'CDROM' }
		def nextSataIndex = (vmBody?.spec?.resources?.disk_list.findAll { it.device_properties?.disk_address?.adapter_type == 'SATA' }?.collect { it.device_properties?.disk_address?.device_index ?: 0 }?.max() ?: 0) + 1

		if(cdromDisk) {
			cdromDisk.data_source_reference = [kind: 'image', uuid: imageUuid]
		} else {
			vmBody?.spec?.resources?.disk_list?.add([
					device_properties: [
						device_type: 'CDROM',
						disk_address: [
							"device_index": nextSataIndex,
							"adapter_type": "SATA"
						],
					],
					data_source_reference: [kind: 'image', uuid: imageUuid]
			])
		}

		return updateVm(client, authConfig, vmUuid, vmBody)
	}

	static ServiceResponse ejectCdrom(HttpApiClient client, Map authConfig, String vmUuid) {
		log.debug("ejectCdrom")
		def vmResults = waitForPowerState(client, authConfig, vmUuid) //get latest spec information
		if(vmResults.success && vmResults.data) {
			def vmBody = vmResults.data
			def cdromDisks = vmBody?.spec?.resources?.disk_list?.findAll { it.device_properties?.device_type?.toLowerCase() == 'cdrom' }
			if(cdromDisks) {
				cdromDisks.each { disk ->
					//disk['device_properties']['is_empty'] = true //does not work
					disk?.remove('data_source_reference')
					disk?.remove('disk_size_bytes')
					disk?.remove('disk_size_mib')
				}
				return updateVm(client, authConfig, vmUuid, vmBody)
			}
		}
		return ServiceResponse.success()
	}

	private static ServiceResponse callListApi(HttpApiClient client, String kind, String path, Map authConfig) {
		log.debug("callListApi: kind ${kind}, path: ${path}")
		def rtn = new ServiceResponse(success: false)
		try {
			def hasMore = true
			def maxResults = 250
			rtn.data = []
			def offset = 0
			def attempt = 0
			while(hasMore && attempt < 100) {
				def body = [kind: kind, offset: offset, length: maxResults]
				def now = new Date().time
				def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/${path}", authConfig.username, authConfig.password,
						new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], body:body, contentType: ContentType.APPLICATION_JSON, ignoreSSL: true, timeout: authConfig.timeout), 'POST')
				log.debug("callListApi results: ${results.toMap()}")
				if(results?.success && !results?.hasErrors()) {
					rtn.success = true
					def pageResults = results.data

					if(pageResults?.entities?.size() > 0) {
						rtn.data += pageResults.entities
						if(pageResults.metadata.length == null) {
							hasMore = false // For some reason.. the clusters/list path (and possibly others) do not return a metadata.length (so assume we got them all)
						} else {
							hasMore = (pageResults.metadata.offset ?: 0) + pageResults.metadata.length < pageResults.metadata.total_matches
						}
						if(hasMore)
							offset += maxResults
					} else {
						hasMore = false
					}
				} else {
					if(!rtn.success) {
						rtn.msg = results.data.message_list?.collect { it.message }?.join(' ')
					}
					hasMore = false
				}
				attempt++
			}

			return rtn
		} catch(e) {
			log.error "Error in callListApi: ${e}", e
		}
		return rtn
	}

	private static ServiceResponse callGroupApi(HttpApiClient client, String entityType, String sortAttribute, List<String> groupMemberAttributes, Map authConfig, Map appendToBody = [:]) {
		log.debug("callGroupApi: ${entityType}")
		def rtn = new ServiceResponse(success: false)
		try {
			def hasMore = true
			def maxResults = 250
			rtn.data = []
			def offset = 0
			def attempt = 0
			def body = [
					entity_type                : entityType,
					group_offset               : 0,
					group_count                : 1,
					group_member_count         : maxResults,
					group_member_offset        : 0,
					group_member_sort_attribute: sortAttribute,
					group_member_attributes    : groupMemberAttributes.collect { [attribute: it] }
			] + appendToBody

			while(hasMore && attempt < 100) {
				body.group_member_offset = offset
				body.group_member_count = maxResults
				def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/groups", authConfig.username, authConfig.password,
						new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], body:body, contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'POST')
				log.debug("callGroupApi results: ${results.toMap()}")
				if(results?.success && !results?.hasErrors()) {
					rtn.success = true
					def groupResults = results.data.group_results.getAt(0)

					if(groupResults?.entity_results?.size() > 0) {
						rtn.data += groupResults.entity_results
						hasMore = body.group_member_offset + groupResults.entity_results.size() < groupResults.total_entity_count
						if(hasMore)
							offset += maxResults
					} else {
						hasMore = false
					}
				} else {
					if(!rtn.success) {
						rtn.msg = results.data.message_list?.collect { it.message }?.join(' ')
					}
					hasMore = false
				}
				attempt++
			}

			return rtn
		} catch(e) {
			log.error "Error in callGroupApi: ${e}", e
		}
		return rtn
	}

	private static ServiceResponse callListApiV2(HttpApiClient client, String path, Map authConfig) {
		log.debug("callListApiV2: path: ${path}")
		def rtn = new ServiceResponse(success: false)
		try {
			def hasMore = true
			def maxResults = 250
			rtn.data = []
			def page = 1
			def attempt = 0
			while(hasMore && attempt < 100) {
				def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/${path}", authConfig.username, authConfig.password,
						new HttpApiClient.RequestOptions(
								headers:['Content-Type':'application/json'],
								queryParams:[count: maxResults.toString(), page: page.toString()],
								contentType: ContentType.APPLICATION_JSON,
								ignoreSSL: true
						), 'GET')
				log.debug("callListApiV2 results: ${results.toMap()}")
				if(results?.success && !results?.hasErrors()) {
					rtn.success = true
					def pageResults = results.data

					if(pageResults?.entities?.size() > 0) {
						rtn.data += pageResults.entities
						hasMore = pageResults.metadata.end_index < pageResults.metadata.total_entities
						if(hasMore)
							page +=1
					} else {
						hasMore = false
					}
				} else {
					if(!rtn.success) {
						rtn.msg = results.data.message_list?.collect { it.message }?.join(' ')
						rtn.data = [invalidLogin: (results.getErrorCode() == "401")]
					}
					hasMore = false
				}
				attempt++
			}

			return rtn
		} catch(e) {
			log.error "Error in callListApiV2: ${e}", e
		}
		return rtn
	}

	static validateServerConfig(MorpheusContext morpheusContext, apiUrl, username, password, Map opts = [:]) {
		log.debug("validateServerConfig: ${opts}")
		def rtn = [success:false, errors:[]]
		try {
			//template
			if(opts.validateTemplate && !opts.template)
				rtn.errors << [field:'template', msg:'Template is required']
			//network
			if(opts.networkId) {
				// great
			} else if (opts?.networkInterfaces) {
				// JSON (or Map from parseNetworks)
				log.debug("validateServerConfig networkInterfaces: ${opts?.networkInterfaces}")
				opts?.networkInterfaces?.eachWithIndex { nic, index ->
					def networkId = nic.network?.id ?: nic.network.group
					log.debug("network.id: ${networkId}")
					if(!networkId) {
						rtn.errors << [field:'networkInterface', msg:'Network is required']
					}
					if (nic.ipMode == 'static' && !nic.ipAddress) {
						rtn.errors = [field:'networkInterface', msg:'You must enter an ip address']
					}
				}
			} else if (opts?.networkInterface) {
				// UI params
				log.debug("validateServerConfig networkInterface: ${opts.networkInterface}")
				toList(opts?.networkInterface?.network?.id)?.eachWithIndex { networkId, index ->
					log.debug("network.id: ${networkId}")
					if(networkId?.length() < 1) {
						rtn.errors << [field:'networkInterface', msg:'Network is required']
					}
					if (networkInterface[index].ipMode == 'static' && !networkInterface[index].ipAddress) {
						rtn.errors = [field:'networkInterface', msg:'You must enter an ip address']
					}
				}
			} else {
				rtn.errors << [field:'networkId', msg:'Network is required']
			}
			if(opts.nodeCount != null && opts.nodeCount == ''){
				rtn.errors << [field:'config.nodeCount', msg:'Number of Hosts Required']
			}
			rtn.success = rtn.errors.size() == 0
		} catch(e) {
			log.error "validateServerConfig error: ${e}", e
		}
		return rtn
	}

	static toList(value) {
		[value].flatten()
	}


	static Map waitForPowerState(HttpApiClient client, Map authConfig, String vmId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def serverDetail = getVm(client, authConfig, vmId)
				log.debug("serverDetail: ${serverDetail}")
				if(!serverDetail.success && serverDetail.data.code == 404 ) {
					pending = false
				}
				def serverResource = serverDetail?.data?.status?.resources
				if(serverDetail.success == true && serverResource.power_state) {
					rtn.success = true
					rtn.data = serverDetail.data
					rtn.powerState = serverResource.power_state
					pending = false
				}
				attempts ++
				if(attempts > 60)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static checkServerReady(HttpApiClient client, Map authConfig, String vmId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def serverDetail = getVm(client, authConfig, vmId)
				log.debug("serverDetail: ${serverDetail}")
				def serverResource = serverDetail?.data?.status?.resources
				if(serverDetail.success == true && serverResource.power_state == 'ON' && serverResource.nic_list?.size() > 0 && serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{checkIpv4Ip(it)} ) {
					rtn.success = true
					rtn.virtualMachine = serverDetail.data
					rtn.ipAddress = serverResource.nic_list.collect { it.ip_endpoint_list }.collect {it.ip}.flatten().find{checkIpv4Ip(it)}
					rtn.diskList = serverResource.disk_list
					rtn.nicList = serverResource.nic_list ?: []
					if(serverResource.nic_list_status && serverResource.nic_list_status instanceof List) {
						rtn.nicList += serverResource.nic_list_status
					}
					rtn.name = serverDetail?.data?.spec?.name
					pending = false
				}
				attempts ++
				if(attempts > 60)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static checkTaskReady(HttpApiClient client, Map authConfig, String taskId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 20l)
				def taskDetail = getTask(client, authConfig, taskId)
				log.debug("taskDetail: ${taskDetail}")
				def taskStatus = taskDetail?.data?.status
				if(taskDetail.success == true && taskStatus) {
					if(taskStatus == 'SUCCEEDED') {
						rtn.success = true
						rtn.data = taskDetail.data
						pending = false
					} else if (taskStatus == 'FAILED') {
						rtn.success = false
						rtn.data = taskDetail.data
						pending = false
					}
				}
				attempts ++
				if(attempts > 60)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static checkIpv4Ip(ipAddress) {
		def rtn = false
		if(ipAddress) {
			if(ipAddress.indexOf('.') > 0 && !ipAddress.startsWith('169'))
				rtn = true
		}
		return rtn
	}

	private static convertNicListTov4(List nicList) {
		def newNicList = nicList.collect { nic ->
			def nicMap = [
				backingInfo: [
			    	isConnected: nic.is_connected,
				],
				networkInfo: [
				    subnet: [
				        extId: nic.subnet_reference.uuid
				    ],
				]
			]
			if(nic["ip_endpoint_list"]) {
				nicMap["networkInfo"]["ipv4Config"] = [
					ipAddress: [
						value: nic["ip_endpoint_list"][0].ip
					]
				]
			}
			return nicMap
		}
	}

	private static ServiceResponse callApi(String path, HttpApiClient client, Map authConfig, String method, Map headers = null, Map body = null, Map opts = [:]) {
		def contentType = opts.contentType ?: ContentType.APPLICATION_JSON
		def ignoreSsl = opts.ignoreSSL ?: true

		HttpApiClient.RequestOptions requestOptions = new HttpApiClient.RequestOptions()
		if(headers) {
			requestOptions.headers = headers
		}
		if(body) {
			requestOptions.body = body
		}
		requestOptions.contentType = contentType
		requestOptions.ignoreSSL = ignoreSsl

		return client.callJsonApi(authConfig.apiUrl?.toString(), path, authConfig.username?.toString(), authConfig.password?.toString(), requestOptions, method)
	}

	private static ServiceResponse callRetryableApi(String path, HttpApiClient client, Map authConfig, String method, Map headers = null, Map body = null, Map opts = [:], RetryUtility retryUtility = null) {
		if(!retryUtility) {
			retryUtility = getLinearRetryUtility()
		}
		def retryClosure = { RetryUtility ru ->
			def currentAttempt = ru.getCurrentAttempt()
			def maxAttempts = ru.getMaxAttempts()
			log.debug("callRetryableApi attempt: ${currentAttempt}, maxAttempts: ${maxAttempts - 1}")
			def rtn = callApi(path, client, authConfig, method, headers, body, opts)
			if(isApiRetryRequired(rtn) && (currentAttempt < maxAttempts - 1)) { //if reaching max attempts then just return the original results of API
				throw retryException
			}
			return rtn
		}
		RetryableFunction rf = new RetryableFunction(retryClosure, retryUtility)
		try {
			def results = retryUtility.execute(rf)
			if (results instanceof ServiceResponse) {
				return results
			} else {
				return ServiceResponse.error("Unable to obtains results from retryable API")
			}
		} catch (RetryException e) {
			return ServiceResponse.error("Unable to obtain results from retryable API")
		}
	}

	private static Map refreshVmBody(HttpApiClient client, Map authConfig, String uuid, Map vmBody) {
		log.debug("refreshVmBody: {}, {}, {}, {}", client, authConfig, uuid, vmBody)
		Map vmResults = waitForPowerState(client, authConfig, uuid) //get latest spec information
		log.debug("refresh results: {}", vmResults)
		Map vmResource = vmBody
		if (vmResults.success) {
			if(vmResults instanceof Map) {
				vmResource = vmResults.data as Map
				log.debug("Obtained new vm body: {}", vmResource)
			}
		}
		return vmResource
	}

	private static Boolean isApiRetryRequired(ServiceResponse results) {
		def rtn = false
		log.debug("isApiRetryRequired: {}", results)
		log.debug("Error code: {}", results.getErrorCode())
		log.debug("results.data: {}", results.data)
		if (results.getErrorCode() == "409" || results?.data?.code == 409) {
			if (results?.data?.message_list && results?.data?.message_list?.find { it?.reason?.equals("CONCURRENT_REQUESTS_NOT_ALLOWED") || it.message?.equals("Edit conflict: please retry change.") }) {
				//we should retry this option
				rtn = true
				log.debug("retry required")
			}
		}
		return rtn
	}

	private static RetryUtility getExponentialRetryUtility(Long initialSleepTime = 500l, Long maxAttempts = 5l) {
		RetryUtility retryUtility
		AbstractRetryDelayPolicy delayPolicy = new ExponentialRetryDelayPolicy()
		delayPolicy.setInitialSleepTime(initialSleepTime)
		delayPolicy.setMaxSleepTime(15000l)
		delayPolicy.setMultiplier(2)
		retryUtility = new RetryUtility(delayPolicy)
		retryUtility.setMaxAttempts(maxAttempts)
		retryUtility.setRetryableErrors([(getRetryExceptionClass()): []])

		return retryUtility
	}

	private static RetryUtility getSimpleRetryUtility(Long initialSleepTime = 1000l, Long maxAttempts = 5l) {
		RetryUtility retryUtility
		AbstractRetryDelayPolicy delayPolicy = new SimpleRetryDelayPolicy()
		retryUtility = new RetryUtility(delayPolicy)
		delayPolicy.setInitialSleepTime(initialSleepTime)
		retryUtility.setMaxAttempts(maxAttempts)
		retryUtility.setRetryableErrors([(getRetryExceptionClass()): []])

		return retryUtility
	}

	private static RetryUtility getLinearRetryUtility(Long initialSleepTime = 1000l, Long maxAttempts = 30l) {
		RetryUtility retryUtility
		AbstractRetryDelayPolicy delayPolicy = new LinearRetryDelayPolicy()
		delayPolicy.setInitialSleepTime(initialSleepTime)
		retryUtility = new RetryUtility(delayPolicy)
		retryUtility.setMaxAttempts(maxAttempts)
		retryUtility.setRetryableErrors([(getRetryExceptionClass()): []])

		return retryUtility
	}

	private static Exception getRetryException() {
		return new PrismRetryException()
	}

	private static getRetryExceptionClass() {
		return getRetryException().getClass()
	}

}

class PrismRetryException extends Exception {
	public PrismRetryException() {
		super()
	}
	public PrismRetryException(String message) {
		super(message)
	}
	public PrismRetryException(String message, Throwable cause) {
		super(message, cause)
	}
}
