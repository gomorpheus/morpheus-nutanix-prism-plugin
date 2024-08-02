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

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.nutanix.prism.plugin.backup.NutanixPrismBackupProvider
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismPlugin extends Plugin {

	private String cloudProviderCode

	@Override
	String getCode() {
		return 'morpheus-nutanix-prism'
	}

	@Override
	void initialize() {
		this.setName('Nutanix Prism Central')
		def nutanixProvision = new NutanixPrismProvisionProvider(this, this.morpheus)
		def nutanixPrismCloud = new NutanixPrismCloudProvider(this, this.morpheus)
		def backupProvider = new NutanixPrismBackupProvider(this, morpheus)
		def nutanixPrismOptionSourceProvider = new NutanixPrismOptionSourceProvider(this, morpheus)
		def iacResourceMappingProvider = new NutanixPrismIacResourceMappingProvider(this, morpheus)

		registerProviders(
			nutanixPrismCloud, nutanixProvision, nutanixPrismOptionSourceProvider, backupProvider, iacResourceMappingProvider
		)

		cloudProviderCode = nutanixPrismCloud.code
		pluginProviders.put(backupProvider.code, backupProvider)
	}

	@Override
	void onDestroy() {

	}

	def MorpheusContext getMorpheusContext() {
		this.morpheus
	}

	def getAuthConfig(Cloud cloud) {
		log.debug "getAuthConfig: ${cloud}"
		def url = cloud.serviceUrl ?: cloud.configMap.apiUrl
		def rtn = [
				apiUrl: getApiUrl(url as String),
				basePath: 'api/nutanix/v3',
				v2basePath: 'api/nutanix/v2.0',
				username: null,
				password: null,
			  vmmApiVersion: NutanixPrismComputeUtility.VMM_API_VERSION.V4_0_A1
		]
		String vmmApiVersionCode = cloud.configMap?.vmmApiVersion?.toString()
		if(vmmApiVersionCode) {
			def vmmApiVersion = NutanixPrismComputeUtility.VMM_API_VERSION.findByCode(vmmApiVersionCode)
			if(vmmApiVersion) {
				rtn.vmmApiVersion = vmmApiVersion
			}
		}
		if(!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = this.morpheus.async.cloud.loadCredentials(cloud.id).blockingGet()
			} catch(e) {
				// If there is no credential on the cloud, then this will error
				// TODO: Change to using 'maybe' rather than 'blockingGet'?
			}
			cloud.accountCredentialLoaded = true
			cloud.accountCredentialData = accountCredential?.data
		}

		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
			rtn.username = cloud.accountCredentialData['username']
		} else {
			rtn.username = cloud.configMap.username ?: cloud.serviceUsername
		}
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			rtn.password = cloud.accountCredentialData['password']
		} else {
			rtn.password = cloud.configMap.password ?: cloud.servicePassword
		}

		return rtn
	}

	NutanixPrismCloudProvider getCloudProvider() {
		return this.getProviderByCode(cloudProviderCode) as NutanixPrismCloudProvider
	}

	static getApiUrl(String apiUrl) {
		if(apiUrl) {
			def rtn = apiUrl
			if(!rtn.startsWith('http'))
				rtn = 'https://' + rtn

			if(!rtn.endsWith('/'))
				rtn = rtn + '/'

			return rtn
		}
	}
}
