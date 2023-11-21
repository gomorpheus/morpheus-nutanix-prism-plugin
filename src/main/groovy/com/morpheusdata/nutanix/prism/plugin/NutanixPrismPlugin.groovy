package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.nutanix.prism.plugin.backup.NutanixPrismBackupProvider
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

		registerProviders(
			nutanixPrismCloud, nutanixProvision, nutanixPrismOptionSourceProvider, backupProvider
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
				password: null
		]

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
			rtn.username = cloud.serviceUsername ?: cloud.configMap.username
		}
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			rtn.password = cloud.accountCredentialData['password']
		} else {
			rtn.password = cloud.servicePassword ?: cloud.configMap.password
		}

		return rtn
	}

	def NutanixPrismCloudProvider getCloudProvider() {
		this.getProviderByCode(cloudProviderCode)
	}

	static getApiUrl(String apiUrl) {
		if(apiUrl) {
			def rtn = apiUrl
			if(rtn.startsWith('http') == false)
				rtn = 'https://' + rtn

			if(rtn.endsWith('/') == false)
				rtn = rtn + '/'

			return rtn
		}
	}
}
