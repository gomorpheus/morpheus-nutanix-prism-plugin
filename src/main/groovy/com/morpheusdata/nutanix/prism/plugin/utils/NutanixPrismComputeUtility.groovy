package com.morpheusdata.nutanix.prism.plugin.utils

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import org.apache.http.entity.ContentType

@Slf4j
class NutanixPrismComputeUtility {

	static ServiceResponse listHosts(HttpApiClient client, Map authConfig) {
		log.debug("listHosts")
		return callListApi(client, 'cluster', 'clusters/list', authConfig)
	}

	static ServiceResponse listVMs(HttpApiClient client, Map authConfig) {
		log.debug("listVMs")
		return callListApi(client, 'vm', 'vms/list', authConfig)
	}

	private static ServiceResponse callListApi(HttpApiClient client, String kind, String path, Map authConfig) {
		log.debug("callListApi")
		def rtn = new ServiceResponse(success: false)
		try {
			def hasMore = true
			def maxResults = 250
			rtn.data = []
			def offset = 0
			def attempt = 0
			while(hasMore && attempt < 100) {
				def body = [kind: kind, offset: offset, length: maxResults]
				def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.basePath}/${path}", authConfig.username, authConfig.password,
						new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json'], body:body, contentType: ContentType.APPLICATION_JSON, ignoreSSL: true), 'POST')
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
}
