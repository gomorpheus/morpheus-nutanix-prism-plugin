package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.core.backup.BackupProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.nutanix.prism.plugin.sync.CategoriesSync
import com.morpheusdata.nutanix.prism.plugin.sync.ClustersSync
import com.morpheusdata.nutanix.prism.plugin.sync.DatastoresSync
import com.morpheusdata.nutanix.prism.plugin.sync.HostsSync
import com.morpheusdata.nutanix.prism.plugin.sync.ImagesSync
import com.morpheusdata.nutanix.prism.plugin.sync.NetworksSync
import com.morpheusdata.nutanix.prism.plugin.sync.SnapshotsSync
import com.morpheusdata.nutanix.prism.plugin.sync.VirtualMachinesSync
import com.morpheusdata.nutanix.prism.plugin.sync.VirtualPrivateCloudSync
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import java.security.MessageDigest

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
		OptionType apiUrl = new OptionType(
				name: 'Api Url',
				code: 'nutanix-prism-api-url',
				fieldName: 'serviceUrl',
				displayOrder: 0,
				fieldLabel: 'Api Url',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'domain'
		)
		OptionType credentials = new OptionType(
				code: 'nutanix-prism-credential',
				inputType: OptionType.InputType.CREDENTIAL,
				name: 'Credentials',
				fieldName: 'type',
				fieldLabel: 'Credentials',
				fieldContext: 'credential',
				required: true,
				defaultValue: 'local',
				displayOrder: 10,
				optionSource: 'credentials',
				config: '{"credentialTypes":["username-password"]}'
		)
		OptionType username = new OptionType(
				name: 'Username',
				code: 'nutanix-prism-username',
				fieldName: 'serviceUsername',
				displayOrder: 20,
				fieldLabel: 'Username',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'domain',
				localCredential: true
		)
		OptionType password = new OptionType(
				name: 'Password',
				code: 'nutanix-prism-password',
				fieldName: 'servicePassword',
				displayOrder: 25,
				fieldLabel: 'Password',
				required: true,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'domain',
				localCredential: true
		)

		OptionType inventoryInstances = new OptionType(
				name: 'Inventory Existing Instances',
				code: 'nutanix-prism-import-existing',
				fieldName: 'importExisting',
				displayOrder: 90,
				fieldLabel: 'Inventory Existing Instances',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)

		OptionType enableVnc = new OptionType(
				name: 'Enable Hypervisor Console',
				code: 'nutanix-prism-enableVnc',
				fieldName: 'enableVnc',
				displayOrder: 91,
				fieldLabel: 'Enable Hypervisor Console',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)

		[apiUrl, credentials, username, password, inventoryInstances, enableVnc]
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		ComputeServerType hypervisorType = new ComputeServerType()
		hypervisorType.name = 'Nutanix Prism Hypervisor'
		hypervisorType.code = 'nutanix-prism-hypervisor'
		hypervisorType.description = 'Nutanix Prism Hypervisor'
		hypervisorType.vmHypervisor = true
		hypervisorType.controlPower = false
		hypervisorType.reconfigureSupported = false
		hypervisorType.externalDelete = false
		hypervisorType.hasAutomation = false
		hypervisorType.agentType = ComputeServerType.AgentType.none
		hypervisorType.platform = PlatformType.esxi
		hypervisorType.managed = false
		hypervisorType.provisionTypeCode = 'nutanix-prism-provision-provider'

		ComputeServerType serverType = new ComputeServerType()
		serverType.name = 'Nutanix Prism Server'
		serverType.code = 'nutanix-prism-server'
		serverType.description = 'Nutanix Prism Server'
		serverType.reconfigureSupported = false
		serverType.hasAutomation = false
		serverType.supportsConsoleKeymap = true
		serverType.platform = PlatformType.none
		serverType.managed = false
		serverType.provisionTypeCode = 'nutanix-prism-provision-provider'

		ComputeServerType vmType = new ComputeServerType()
		vmType.name = 'Nutanix Prism Linux VM'
		vmType.code = 'nutanix-prism-vm'
		vmType.description = 'Nutanix Prism Linux VM'
		vmType.reconfigureSupported = false
		vmType.hasAutomation = true
		vmType.supportsConsoleKeymap = true
		vmType.platform = PlatformType.linux
		vmType.managed = true
		vmType.provisionTypeCode = 'nutanix-prism-provision-provider'

		[hypervisorType, serverType, vmType]
	}

	@Override
	Collection<ProvisioningProvider> getAvailableProvisioningProviders() {
		return plugin.getProvidersByType(ProvisioningProvider) as Collection<ProvisioningProvider>
	}

	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		return plugin.getProvidersByType(BackupProvider) as Collection<com.morpheusdata.core.backup.BackupProvider>
	}

	@Override
	ProvisioningProvider getProvisioningProvider(String providerCode) {
		return getAvailableProvisioningProviders().find { it.code == providerCode }
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		NetworkType vlanNetwork = new NetworkType([
				code              : 'nutanix-prism-vlan-network',
				externalType      : 'VLAN',
				cidrEditable      : true,
				dhcpServerEditable: true,
				dnsEditable       : true,
				gatewayEditable   : true,
				vlanIdEditable    : true,
				canAssignPool     : true,
				name              : 'Nutanix Prism VLAN Network'
		])
		NetworkType overlayNetwork = new NetworkType([
				code              : 'nutanix-prism-overlay-network',
				externalType      : 'OVERLAY',
				cidrEditable      : true,
				dhcpServerEditable: true,
				dnsEditable       : true,
				gatewayEditable   : true,
				vlanIdEditable    : true,
				canAssignPool     : true,
				name              : 'Nutanix Prism Overlay Network'
		])
		[vlanNetwork, overlayNetwork]
	}

	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		return null
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		def volumeTypes = []
		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-datastore',
				name: 'Nutanix Prism Datastore'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-host-disk',
				name: 'Nutanix Prism Host Disk'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk',
				name: 'Disk'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk-scsi',
				externalId: 'SCSI',
				name: 'SCSI'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk-pci',
				externalId: 'PCI',
				name: 'PCI'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk-ide',
				externalId: 'IDE',
				name: 'IDE'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk-sata',
				externalId: 'SATA',
				name: 'SATA'
		])

		volumeTypes
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
				def username
				def password
				if(validateCloudRequest.credentialType?.toString().isNumber()) {
					AccountCredential accountCredential = morpheus.accountCredential.get(validateCloudRequest.credentialType.toLong()).blockingGet()
					password = accountCredential.data.password
					username = accountCredential.data.username
				} else if(validateCloudRequest.credentialType == 'username-password') {
					password = validateCloudRequest.credentialPassword ?: cloudInfo.servicePassword
					username = validateCloudRequest.credentialUsername ?: cloudInfo.serviceUsername
				} else if(validateCloudRequest.credentialType == 'local') {
					if(validateCloudRequest.opts?.zone?.servicePassword && validateCloudRequest.opts?.zone?.servicePassword != '************') {
						password = validateCloudRequest.opts?.zone?.servicePassword
					} else {
						password = cloudInfo.servicePassword
					}
					username = validateCloudRequest.opts?.zone?.serviceUsername ?: cloudInfo.serviceUsername
				}

				if(username?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a username')
				} else if(password?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a password')
				} else if(cloudInfo.serviceUrl?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter an api url')
				} else {
					//test api call
					def apiUrl = plugin.getApiUrl(cloudInfo.serviceUrl)
					//get creds
					Map authConfig = [apiUrl: apiUrl, basePath: 'api/nutanix/v3', v2basePath: 'api/nutanix/v2.0', username: username, password: password]
					HttpApiClient apiClient = new HttpApiClient()
					def clusterList = NutanixPrismComputeUtility.listHostsV2(apiClient, authConfig)
					if(clusterList.success == true) {
						return ServiceResponse.success()
					} else {
						return new ServiceResponse(success: false, msg: 'Invalid credentials')
					}
				}
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
		initializeCloud(cloudInfo)
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
		return true
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasFolders() {
		return false
	}

	@Override
	Boolean hasDatastores() {
		return true
	}

	@Override
	Boolean hasBareMetal() {
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
		return 'nutanix-prism-cloud'
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"nutanix-prism.svg", darkPath: "nutanix-prism-dark.svg")
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"nutanix-prism-plugin-circular.svg", darkPath: "nutanix-prism-plugin-circular-dark.svg")
	}

	@Override
	String getName() {
		return 'Nutanix Prism'
	}

	@Override
	String getDescription() {
		return 'Nutanix Prism Central'
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		log.debug("startServer: ${computeServer}")
		def rtn = [success:false]
		try {
			return nutanixPrismProvisionProvider().startServer(computeServer)
		} catch(e) {
			rtn.msg = "Error starting server: ${e.message}"
			log.error("startServer error: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		log.debug("stopServer: ${computeServer}")
		def rtn = [success:false]
		try {
			return nutanixPrismProvisionProvider().stopServer(computeServer)
		} catch(e) {
			rtn.msg = "Error stoping server: ${e.message}"
			log.error("stopServer error: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		log.debug("deleteServer: ${computeServer}")
		def rtn = [success:false]
		try {
			return nutanixPrismProvisionProvider().deleteServer(computeServer)
		} catch(e) {
			rtn.msg = "Error deleting server: ${e.message}"
			log.error("deleteServer error: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	Boolean hasCloudInit() {
		return true
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

		HttpApiClient client

		try {
			NetworkProxy proxySettings = cloud.apiProxy
			client = new HttpApiClient()
			client.networkProxy = proxySettings

			def authConfig = plugin.getAuthConfig(cloud)
			def apiUrlObj = new URL(authConfig.apiUrl)
			def apiHost = apiUrlObj.getHost()
			def apiPort = apiUrlObj.getPort() > 0 ? apiUrlObj.getPort() : (apiUrlObj?.getProtocol()?.toLowerCase() == 'https' ? 443 : 80)
			def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, true, true, proxySettings)
			log.debug("nutanix prism central online: {} - {}", apiHost, hostOnline)
			if(hostOnline) {
				def testResults = NutanixPrismComputeUtility.testConnection(client, authConfig)
				if(testResults.success == true) {
					def doInventory = cloud.getConfigProperty('importExisting')
					Boolean createNew = false
					if(doInventory == 'on' || doInventory == 'true' || doInventory == true) {
						createNew = true
					}
					ensureRegionCode(cloud)

					(new CategoriesSync(this.plugin, cloud, client)).execute()
					(new VirtualPrivateCloudSync(this.plugin, cloud, client)).execute()
					(new ClustersSync(this.plugin, cloud, client)).execute()
					(new DatastoresSync(this.plugin, cloud, client)).execute()
					(new NetworksSync(this.plugin, cloud, client)).execute()
					(new ImagesSync(this.plugin, cloud, client)).execute()
					(new HostsSync(this.plugin, cloud, client)).execute()
					(new VirtualMachinesSync(this.plugin, cloud, client, createNew)).execute()
					(new SnapshotsSync(this.plugin, cloud, client)).execute()

					rtn = ServiceResponse.success()
				}
				else {
					rtn = ServiceResponse.error(testResults.invalidLogin == true ? 'invalid credentials' : 'error connecting')
				}
			} else {
				rtn = ServiceResponse.error('Nutanix Prism Central is not reachable', null, [status: Cloud.Status.offline])
			}
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}

		return rtn
	}

	private ensureRegionCode(Cloud cloud) {
		def authConfig = plugin.getAuthConfig(cloud)
		def apiUrl = authConfig.apiUrl
		def regionString = "${apiUrl}"
		MessageDigest md = MessageDigest.getInstance("MD5")
		md.update(regionString.bytes)
		byte[] checksum = md.digest()
		def regionCode = checksum.encodeHex().toString()
		if (cloud.regionCode != regionCode) {
			cloud.regionCode = regionCode
			morpheusContext.cloud.save(cloud).blockingGet()
		}
	}

	NutanixPrismProvisionProvider nutanixPrismProvisionProvider() {
		this.plugin.getProviderByCode('nutanix-prism-provision-provider')
	}
}
