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
		def nutanixPrismCloud = new NutanixPrismCloudProvider(this, this.morpheus)
		cloudProviderCode = nutanixPrismCloud.code
		def nutanixPrismOptionSourceProvider = new NutanixPrismOptionSourceProvider(this, morpheus)

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
		def rtn = [:]

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

		def username
		def password
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
			username = cloud.accountCredentialData['username']
		} else {
			username = cloud.serviceUsername
		}
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			password = cloud.accountCredentialData['password']
		} else {
			password = cloud.servicePassword
		}

		return rtn
	}
}
