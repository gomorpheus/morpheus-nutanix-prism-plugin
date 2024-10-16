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

import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.IacResourceMappingProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.backup.BackupProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.MetadataTagIdentityProjection
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.nutanix.prism.plugin.sync.CategoriesSync
import com.morpheusdata.nutanix.prism.plugin.sync.ClustersSync
import com.morpheusdata.nutanix.prism.plugin.sync.DatastoresSync
import com.morpheusdata.nutanix.prism.plugin.sync.HostsSync
import com.morpheusdata.nutanix.prism.plugin.sync.ImagesSync
import com.morpheusdata.nutanix.prism.plugin.sync.NetworksSync
import com.morpheusdata.nutanix.prism.plugin.sync.ProjectsSync
import com.morpheusdata.nutanix.prism.plugin.sync.SnapshotsSync
import com.morpheusdata.nutanix.prism.plugin.sync.TemplatesSync
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

		OptionType project = new OptionType(
			name: 'Project',
			code: 'nutanix-prism-project',
			fieldName: 'project',
			displayOrder: 30,
			fieldLabel: 'Project',
			required: false,
			inputType: OptionType.InputType.SELECT,
			fieldContext: 'config',
			optionSource: 'nutanixPrismProjects',
			dependsOnCode: 'zone.serviceUrl, serviceUrl, zone.serviceUsername, serviceUsername, zone.servicePassword, servicePassword, credential.type, credential.username, credential.password'
		)

		OptionType vmmApiVersion = new OptionType(
						name: 'VMM API Version',
						code: 'nutanix-prism-vmm-api-version',
						fieldName: 'vmmApiVersion',
						displayOrder: 31,
						fieldLabel: 'VMM API Version',
						required: true,
						inputType: OptionType.InputType.SELECT,
						fieldContext: 'config',
						optionSource: 'supportedVmmApiVersions',
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

		return [apiUrl, credentials, username, password, project, vmmApiVersion, inventoryInstances, enableVnc]
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		ComputeServerType hypervisorType = new ComputeServerType()
		hypervisorType.name = 'Nutanix Prism Central Hypervisor'
		hypervisorType.code = 'nutanix-prism-hypervisor'
		hypervisorType.description = 'Nutanix Prism Central Hypervisor'
		hypervisorType.vmHypervisor = true
		hypervisorType.controlPower = false
		hypervisorType.reconfigureSupported = false
		hypervisorType.externalDelete = false
		hypervisorType.hasAutomation = false
		hypervisorType.agentType = ComputeServerType.AgentType.none
		hypervisorType.platform = PlatformType.esxi
		hypervisorType.managed = false
		hypervisorType.provisionTypeCode = 'nutanix-prism-provision-provider'
		hypervisorType.nodeType = 'nutanix-prism-node'

		ComputeServerType serverType = new ComputeServerType()
		serverType.name = 'Nutanix Prism Central Server'
		serverType.code = 'nutanix-prism-server'
		serverType.description = 'Nutanix Prism Central Server'
		serverType.reconfigureSupported = true
		serverType.hasAutomation = false
		serverType.supportsConsoleKeymap = true
		serverType.platform = PlatformType.none
		serverType.managed = true
		serverType.guestVm = true
		serverType.provisionTypeCode = 'nutanix-prism-provision-provider'
		serverType.nodeType = 'morpheus-vm-node'

		ComputeServerType vmType = new ComputeServerType()
		vmType.name = 'Nutanix Prism Central Linux VM'
		vmType.code = 'nutanix-prism-vm'
		vmType.description = 'Nutanix Prism Central Linux VM'
		vmType.reconfigureSupported = true
		vmType.hasAutomation = true
		vmType.supportsConsoleKeymap = true
		vmType.platform = PlatformType.linux
		vmType.managed = true
		vmType.guestVm = true
		vmType.provisionTypeCode = 'nutanix-prism-provision-provider'
		vmType.nodeType = 'morpheus-vm-node'

		ComputeServerType windowsType = new ComputeServerType()
		windowsType.name = 'Nutanix Prism Central Windows VM'
		windowsType.code = 'nutanix-prism-windows-vm'
		windowsType.description = 'Nutanix Prism Central Windows VM'
		windowsType.reconfigureSupported = true
		windowsType.hasAutomation = true
		windowsType.supportsConsoleKeymap = true
		windowsType.platform = PlatformType.windows
		windowsType.managed = true
		windowsType.guestVm = true
		windowsType.provisionTypeCode = 'nutanix-prism-provision-provider'
		windowsType.nodeType = 'morpheus-windows-vm-node'

		ComputeServerType unmanagedType = new ComputeServerType()
		unmanagedType.name = 'Nutanix Prism Instance'
		unmanagedType.code = 'nutanix-prism-unmanaged'
		unmanagedType.description = 'Nutanix Prism Instance'
		unmanagedType.reconfigureSupported = false
		unmanagedType.hasAutomation = true
		unmanagedType.supportsConsoleKeymap = true
		unmanagedType.platform = PlatformType.linux
		unmanagedType.managed = false
		unmanagedType.guestVm = true
		unmanagedType.provisionTypeCode = 'nutanix-prism-provision-provider'
		unmanagedType.nodeType = 'unmanaged'
		unmanagedType.managedServerType = 'nutanix-prism-vm'

		ComputeServerType linuxDockerType = new ComputeServerType()
		linuxDockerType.name = 'Nutanix Prism Docker Host'
		linuxDockerType.code = 'nutanix-prism-linux'
		linuxDockerType.description = 'Nutanix Prism Docker Host'
		linuxDockerType.containerHypervisor = true
		linuxDockerType.reconfigureSupported = true
		linuxDockerType.hasAutomation = true
		linuxDockerType.supportsConsoleKeymap = true
		linuxDockerType.platform = PlatformType.linux
		linuxDockerType.managed = true
		linuxDockerType.provisionTypeCode = 'nutanix-prism-provision-provider'
		linuxDockerType.agentType = ComputeServerType.AgentType.host
		linuxDockerType.clusterType = ComputeServerType.ClusterType.docker
		linuxDockerType.containerEngine = ComputeServerType.ContainerEngine.docker
		linuxDockerType.computeTypeCode = 'docker-host'

		ComputeServerType kubeMasterType = new ComputeServerType()
		kubeMasterType.name = 'Nutanix Prism Kubernetes Master'
		kubeMasterType.code = 'nutanix-prism-kube-master'
		kubeMasterType.description = 'Nutanix Prism Kubernetes Master'
		kubeMasterType.containerHypervisor = true
		kubeMasterType.reconfigureSupported = true
		kubeMasterType.hasAutomation = true
		kubeMasterType.supportsConsoleKeymap = true
		kubeMasterType.platform = PlatformType.linux
		kubeMasterType.managed = true
		kubeMasterType.provisionTypeCode = 'nutanix-prism-provision-provider'
		kubeMasterType.agentType = ComputeServerType.AgentType.host
		kubeMasterType.clusterType = ComputeServerType.ClusterType.kubernetes
		kubeMasterType.computeTypeCode = 'kube-master'
		kubeMasterType.nodeType = 'kube-master'

		ComputeServerType kubeWorkerType = new ComputeServerType()
		kubeWorkerType.name = 'Nutanix Prism Kubernetes Worker'
		kubeWorkerType.code = 'nutanix-prism-kube-worker'
		kubeWorkerType.description = 'Nutanix Prism Kubernetes Worker'
		kubeWorkerType.containerHypervisor = true
		kubeWorkerType.reconfigureSupported = true
		kubeWorkerType.hasAutomation = true
		kubeWorkerType.supportsConsoleKeymap = true
		kubeWorkerType.platform = PlatformType.linux
		kubeWorkerType.managed = true
		kubeWorkerType.provisionTypeCode = 'nutanix-prism-provision-provider'
		kubeWorkerType.agentType = ComputeServerType.AgentType.host
		kubeWorkerType.clusterType = ComputeServerType.ClusterType.kubernetes
		kubeWorkerType.computeTypeCode = 'kube-worker'
		kubeWorkerType.nodeType = 'kube-worker'

		[hypervisorType, serverType, vmType, windowsType, unmanagedType, linuxDockerType, kubeMasterType, kubeWorkerType]
	}

	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
		return plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		return plugin.getProvidersByType(BackupProvider) as Collection<com.morpheusdata.core.backup.BackupProvider>
	}

	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
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
				name              : 'Nutanix Prism Central Managed VLAN Network'
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
				name              : 'Nutanix Prism Central Overlay Network'
		])
		NetworkType unmanagedVlanNetwork = new NetworkType([
			code              : 'nutanix-prism-unmanaged-vlan-network',
			externalType      : 'VLAN',
			cidrEditable      : true,
			dhcpServerEditable: true,
			dnsEditable       : true,
			gatewayEditable   : true,
			vlanIdEditable    : true,
			canAssignPool     : true,
			name              : 'Nutanix Prism Central Unmanaged VLAN Network'
		])
		[vlanNetwork, overlayNetwork, unmanagedVlanNetwork]
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
				name: 'Nutanix Prism Central Datastore'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-host-disk',
				name: 'Nutanix Prism Central Host Disk'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk',
				name: 'Disk'
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk-scsi',
				externalId: 'scsi',
				name: 'scsi',
				displayOrder: 1
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk-pci',
				externalId: 'pci',
				name: 'pci',
				displayOrder: 2
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk-ide',
				externalId: 'ide',
				name: 'ide',
				displayOrder: 3
		])

		volumeTypes << new StorageVolumeType([
				code: 'nutanix-prism-disk-sata',
				externalId: 'sata',
				name: 'sata',
				displayOrder: 0
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
				def url = (cloudInfo.serviceUrl ?: cloudInfo.configMap.apiUrl) as String
				if(validateCloudRequest.credentialType?.toString()?.isNumber()) {
					AccountCredential accountCredential = morpheus.async.accountCredential.get(validateCloudRequest.credentialType.toLong()).blockingGet()
					password = accountCredential.data.password
					username = accountCredential.data.username
				} else if(validateCloudRequest.credentialType == 'username-password') {
					password = validateCloudRequest.credentialPassword ?: cloudInfo.configMap.password ?: cloudInfo.servicePassword
					username = validateCloudRequest.credentialUsername ?: cloudInfo.configMap.username ?: cloudInfo.serviceUsername
				} else if(validateCloudRequest.credentialType == 'local') {
					if(validateCloudRequest.opts?.zone?.servicePassword && validateCloudRequest.opts?.zone?.servicePassword != '************') {
						password = validateCloudRequest.opts?.zone?.servicePassword
					} else {
						password = cloudInfo.configMap.password ?: cloudInfo.servicePassword
					}
					username = validateCloudRequest.opts?.zone?.serviceUsername ?: cloudInfo.configMap.username ?: cloudInfo.serviceUsername
				}

				if(username?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a username')
				} else if(password?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a password')
				} else if(url?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter an api url')
				} else {
					//test api call
					def apiUrl = plugin.getApiUrl(url)
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
	ServiceResponse deleteCloud(Cloud cloud) {

		//clean up images and templates
		List<VirtualImageLocationIdentityProjection> virtualImageLocations = morpheusContext.async.virtualImage.location.listIdentityProjections(new DataQuery().withFilters([
			new DataFilter("refType", "ComputeZone"),
			new DataFilter("refId", cloud.id),
			new DataOrFilter(
				new DataFilter("virtualImage.externalType", "template"),
				new DataFilter("virtualImage.externalType", "null")
			)
		]).withJoins('virtualImage')).toList().blockingGet()

		List<VirtualImageIdentityProjection> imagesToRemove = virtualImageLocations.collect { it.virtualImage }.findAll {!it.systemImage}.unique()
		//remove the locations
		morpheusContext.async.virtualImage.location.remove(virtualImageLocations).blockingGet()

		//clean up tags
		List<MetadataTagIdentityProjection> tags = morpheusContext.async.metadataTag.listIdentityProjections(new DataQuery().withFilters([
			new DataFilter("refType", "ComputeZone"),
			new DataFilter("refId", cloud.id),
		])).toList().blockingGet()

		morpheusContext.async.metadataTag.remove(tags).blockingGet()

		List<ComputeServerIdentityProjection> servers = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).toList().blockingGet()

		morpheusContext.async.computeServer.remove(servers).blockingGet()

		return new ServiceResponse(success: true)
	}

	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	@Override
	Boolean provisionRequiresResourcePool() {
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
		return new Icon(path:"nutanix-prism-circular.svg", darkPath: "nutanix-prism-circular-dark.svg")
	}

	@Override
	String getName() {
		return 'Nutanix Prism Central'
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

			//hack to move credentials to new locations
			def updatedCreds = false
			if(cloud.serviceUsername == null && cloud.getConfigProperty('username')) {
				cloud.serviceUsername = cloud.getConfigProperty('username')
				cloud.setConfigProperty('username', null)
				updatedCreds = true
			}
			if(cloud.servicePassword == null && cloud.getConfigProperty('password')) {
				cloud.servicePassword = cloud.getConfigProperty('password')
				cloud.setConfigProperty('password', null)
				updatedCreds = true
			}
			if(cloud.serviceUrl == null && cloud.getConfigProperty('apiUrl')) {
				cloud.serviceUrl = cloud.getConfigProperty('apiUrl')
				cloud.setConfigProperty('apiUrl', null)
				updatedCreds = true
			}
			if(updatedCreds) {
				cloud = morpheusContext.services.cloud.save(cloud)
			}
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
					Map projects = [:]
					def allProjects = NutanixPrismComputeUtility.listProjects(client, authConfig)
					if(allProjects.success) {
						projects.all = allProjects.data as List
					}
					if(cloud.configMap.project && cloud.configMap.project?.toString() != '-1') {
						def projectResponse =  NutanixPrismComputeUtility.getProject(client, authConfig, cloud.configMap.project as String)
						if(projectResponse.success) {
							projects.selected = projectResponse.data as Map
							projects.selected.uuid = cloud.configMap.project
						}

					}

					if(allProjects.success) {
						(new CategoriesSync(this.plugin, cloud, client)).execute()
						(new ProjectsSync(this.plugin, cloud, client, projects)).execute()
						(new VirtualPrivateCloudSync(this.plugin, cloud, client, projects)).execute()
						(new ClustersSync(this.plugin, cloud, client, projects)).execute()
						(new DatastoresSync(this.plugin, cloud, client, projects)).execute()
						(new NetworksSync(this.plugin, cloud, client, projects)).execute()
						(new ImagesSync(this.plugin, cloud, client)).execute()
						(new TemplatesSync(this.plugin, cloud, client)).execute()
						(new HostsSync(this.plugin, cloud, client, projects)).execute()
						(new VirtualMachinesSync(this.plugin, cloud, client, createNew, projects)).execute()
						(new SnapshotsSync(this.plugin, cloud, client)).execute()

					}

					rtn = ServiceResponse.success()
				} else {
					rtn = ServiceResponse.error(testResults.invalidLogin == true ? 'invalid credentials' : 'error connecting', null, [status: Cloud.Status.offline])
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
			morpheusContext.async.cloud.save(cloud).blockingGet()
		}
	}

	NutanixPrismProvisionProvider nutanixPrismProvisionProvider() {
		this.plugin.getProviderByCode('nutanix-prism-provision-provider') as NutanixPrismProvisionProvider
	}

	IacResourceMappingProvider getIacResourceMappingProvider() {
		this.plugin.getProviderByCode('nutanix-prism-iac-resource-mapping-provider') as NutanixPrismIacResourceMappingProvider
	}

	@Override
	Collection<Datastore> filterDatastores(Cloud cloud, Collection<Datastore> datastores, Collection<CloudPool> resourcePools) {
		if(resourcePools.size() > 0 ) {
			def allowedPoolIds = resourcePools.collect { it.externalId }
			return datastores.findAll({ datastore -> datastore.refType == "ComputeServerGroup" || datastore.assignedZonePools.find { it.externalId in allowedPoolIds } })
		}
		return datastores
	}
}
