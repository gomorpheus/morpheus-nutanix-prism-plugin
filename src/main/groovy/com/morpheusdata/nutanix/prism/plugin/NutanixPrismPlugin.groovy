package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismPlugin extends Plugin {

	private String cloudProviderCode

	@Override
	String getCode() {
		return 'morpheus-nutanix-prism-plugin'
	}

	@Override
	void initialize() {
		this.setName('Nutanix Prism Plugin')
		def nutanixProvision = new NutanixPrismProvisionProvider(this, this.morpheus)
		def nutanixPrismCloud = new NutanixPrismCloudProvider(this, this.morpheus)
		cloudProviderCode = nutanixPrismCloud.code
		def nutanixPrismOptionSourceProvider = new NutanixPrismOptionSourceProvider(this, morpheus)

		this.pluginProviders.put(nutanixProvision.code, nutanixProvision)
		this.pluginProviders.put(nutanixPrismCloud.code, nutanixPrismCloud)
		this.pluginProviders.put(nutanixPrismOptionSourceProvider.code, nutanixPrismOptionSourceProvider)
	}

	@Override
	void onDestroy() {

	}

	def MorpheusContext getMorpheusContext() {
		this.morpheus
	}

	def getAuthConfig(Cloud cloud) {
		log.debug "getAuthConfig: ${cloud}"
		def rtn = [apiUrl: getApiUrl(cloud.serviceUrl), basePath: 'api/nutanix/v3', username: null, password: null]

		if(!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = this.morpheus.cloud.loadCredentials(cloud.id).blockingGet()
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
			rtn.username = cloud.serviceUsername
		}
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			rtn.password = cloud.accountCredentialData['password']
		} else {
			rtn.password = cloud.servicePassword
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
