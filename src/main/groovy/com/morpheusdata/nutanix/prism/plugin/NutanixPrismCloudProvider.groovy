package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismCloudProvider implements CloudProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext

	NutanixPrismCloudProvider(NutanixPrismPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		[]
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		return []
	}

	@Override
	Collection<ProvisioningProvider> getAvailableProvisioningProviders() {
		return []
	}

	@Override
	Collection<AbstractBackupProvider> getAvailableBackupProviders() {
		return null
	}

	@Override
	ProvisioningProvider getProvisioningProvider(String providerCode) {
		return null
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		return null
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		return null
	}

	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		return null
	}

	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		log.info("validate: {}", cloudInfo)
		try {
			if(cloudInfo) {
			} else {
				return new ServiceResponse(success: false, msg: 'No cloud found')
			}
		} catch(e) {
			log.error('Error validating cloud', e)
			return new ServiceResponse(success: false, msg: 'Error validating cloud')
		}
	}

	@Override
	ServiceResponse refresh(Cloud cloudInfo) {

	}

	@Override
	void refreshDaily(Cloud cloudInfo) {
		//nothing daily
	}

	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return new ServiceResponse(success: true)
	}

	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	@Override
	Boolean hasNetworks() {
		return false
	}

	@Override
	Boolean hasFolders() {
		return false
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
		return 'nutanix-prism-plugin-cloud'
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"nutanix-prism-plugin.svg", darkPath: "nutanix-prism-plugin-dark.svg")
	}

	@Override
	String getName() {
		return 'Nutanix Prism'
	}

	@Override
	String getDescription() {
		return 'Nutanix Prism Central plugin'
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
	}

	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
	}

	@Override
	Boolean hasCloudInit() {
		return false
	}

	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	@Override
	ServiceResponse initializeCloud(Cloud cloud) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		log.info "Initializing Cloud: ${cloud.code}"
		log.info "config: ${cloud.configMap}"

		try {

		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		}
		return rtn
	}
}
