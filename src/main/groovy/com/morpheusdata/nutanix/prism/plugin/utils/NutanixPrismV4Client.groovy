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

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import org.apache.http.entity.ContentType

/**
 * Shared helper for calling the Nutanix V4 REST API directly via {@link HttpApiClient}.
 *
 * This plugin does not use the generated Nutanix V4 Java SDKs (vmm/prism/networking/microseg
 * java clients). All V4 calls go through the same {@link HttpApiClient} + {@code Map authConfig}
 * pattern already used for V2/V3 calls elsewhere in {@link NutanixPrismComputeUtility} — only the
 * base path and JSON field names differ.
 *
 * Base paths below match Nutanix's published v4.0 REST API structure. Per-resource sub-paths
 * (e.g. "clusters", "vms", "recovery-points") and exact response/pagination field names must be
 * confirmed against a live Prism Central instance before being relied upon in a work item —
 * see the verification strategy in the nutanix-v4-rest-migration spec.
 */
@Slf4j
class NutanixPrismV4Client {

	static final String NETWORKING_V4_BASE_PATH = 'api/networking/v4.0/config'
	static final String MICROSEG_V4_BASE_PATH = 'api/microseg/v4.0/config'
	static final String PRISM_V4_BASE_PATH = 'api/prism/v4.0/config'
	// Clusters and hosts live under the "clustermgmt" namespace, not "prism" (confirmed against
	// Nutanix's published v4 API reference - see nutanix-v4-rest-migration spec).
	static final String CLUSTERMGMT_V4_BASE_PATH = 'api/clustermgmt/v4.0/config'
	// Cluster/host statistics are a separate "stats" sub-namespace (not on the config resource itself),
	// queried per-entity with a time range / $statType, not paginated like list endpoints.
	static final String CLUSTERMGMT_STATS_V4_BASE_PATH = 'api/clustermgmt/v4.0/stats'

	/**
	 * Builds a VMM V4 REST path using the cloud's configured VMM API version
	 * (see {@code NutanixPrismComputeUtility.VMM_API_VERSION} and the "VMM API Version"
	 * cloud option users set on cloud create, threaded through as {@code authConfig.vmmApiVersion}).
	 * Falls back to V4_0 if not set.
	 */
	static String buildVmmV4Path(Map authConfig, String resourcePath) {
		NutanixPrismComputeUtility.VMM_API_VERSION apiVersion = authConfig?.vmmApiVersion ?: NutanixPrismComputeUtility.VMM_API_VERSION.V4_0
		return "api/vmm/${apiVersion.getCode()}/ahv/config/${resourcePath}"
	}

	static String buildNetworkingV4Path(String resourcePath) {
		return "${NETWORKING_V4_BASE_PATH}/${resourcePath}"
	}

	static String buildMicrosegV4Path(String resourcePath) {
		return "${MICROSEG_V4_BASE_PATH}/${resourcePath}"
	}

	static String buildPrismV4Path(String resourcePath) {
		return "${PRISM_V4_BASE_PATH}/${resourcePath}"
	}

	static String buildClusterMgmtV4Path(String resourcePath) {
		return "${CLUSTERMGMT_V4_BASE_PATH}/${resourcePath}"
	}

	static String buildClusterMgmtStatsV4Path(String resourcePath) {
		return "${CLUSTERMGMT_STATS_V4_BASE_PATH}/${resourcePath}"
	}

	static Map<String, String> buildV4Headers() {
		return ['Content-Type': 'application/json']
	}

	/**
	 * Performs a GET against a V4 REST list endpoint, paging via {@code $page}/{@code $limit}
	 * query params until all results are collected. Mirrors the shape/behaviour of the existing
	 * {@code callListApi}/{@code callListApiV2} helpers so callers receive a flat {@code List} in
	 * {@code ServiceResponse.data}, unaffected by V4 pagination internals.
	 *
	 * V4 list responses are expected in the form:
	 * <pre>
	 * {
	 *   "data": [ ... ],
	 *   "metadata": { "totalAvailableResults": N, ... }
	 * }
	 * </pre>
	 *
	 * @param client the shared HttpApiClient
	 * @param path the V4 resource path (e.g. result of {@link #buildPrismV4Path}), without query params
	 * @param authConfig the plugin's auth config Map (apiUrl, username, password, ignoreSSL, timeout)
	 * @param queryParams additional query params to send on every page request (e.g. $filter, $orderby, $select)
	 * @param limit page size for $limit
	 */
	static ServiceResponse callListApiV4(HttpApiClient client, String path, Map authConfig, Map queryParams = [:], Integer limit = 50) {
		log.debug("callListApiV4: path: ${path}")
		def rtn = new ServiceResponse(success: false)
		try {
			def hasMore = true
			rtn.data = []
			def page = 0
			def attempt = 0
			while(hasMore && attempt < 100) {
				def pageQueryParams = (['$page': page.toString(), '$limit': limit.toString()] + queryParams)
				def results = client.callJsonApi(authConfig.apiUrl, path, authConfig.username, authConfig.password,
						new HttpApiClient.RequestOptions(
								headers: buildV4Headers(),
								queryParams: pageQueryParams,
								contentType: ContentType.APPLICATION_JSON,
								ignoreSSL: true,
								timeout: authConfig.timeout
						), 'GET')
				log.debug("callListApiV4 results: ${results.toMap()}")
				if(results?.success && !results?.hasErrors()) {
					rtn.success = true
					def pageResults = results.data

					if(pageResults?.data?.size() > 0) {
						rtn.data += pageResults.data
						def totalAvailable = pageResults.metadata?.totalAvailableResults
						hasMore = totalAvailable != null ? (rtn.data.size() < totalAvailable) : false
						if(hasMore)
							page += 1
					} else {
						hasMore = false
					}
				} else {
					if(!rtn.success) {
						rtn.msg = results.data?.error?.collect { it.message }?.join(' ') ?: results.data?.message
						rtn.data = [invalidLogin: (results.getErrorCode() == '401')]
					}
					hasMore = false
				}
				attempt++
			}

			return rtn
		} catch(e) {
			log.error "Error in callListApiV4: ${e}", e
		}
		return rtn
	}

	/**
	 * Performs a GET against a V4 REST single-resource endpoint (e.g. a stats endpoint for one
	 * cluster/host, which is not a paginated list) and unwraps the {@code {metadata, data}} envelope
	 * so {@code ServiceResponse.data} is the resource itself.
	 */
	static ServiceResponse callApiV4(HttpApiClient client, String path, Map authConfig, Map queryParams = [:]) {
		log.debug("callApiV4: path: ${path}")
		def rtn = new ServiceResponse(success: false)
		try {
			def results = client.callJsonApi(authConfig.apiUrl, path, authConfig.username, authConfig.password,
					new HttpApiClient.RequestOptions(
							headers: buildV4Headers(),
							queryParams: queryParams,
							contentType: ContentType.APPLICATION_JSON,
							ignoreSSL: true,
							timeout: authConfig.timeout
					), 'GET')
			log.debug("callApiV4 results: ${results.toMap()}")
			if(results?.success && !results?.hasErrors()) {
				rtn.success = true
				rtn.data = results.data?.data
			} else {
				rtn.msg = results.data?.error?.collect { it.message }?.join(' ') ?: results.data?.message
				rtn.data = [invalidLogin: (results.getErrorCode() == '401')]
			}
		} catch(e) {
			log.error "Error in callApiV4: ${e}", e
		}
		return rtn
	}
}
