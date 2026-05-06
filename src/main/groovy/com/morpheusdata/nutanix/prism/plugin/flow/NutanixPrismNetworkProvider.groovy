/*
 * Copyright 2025 Morpheus Data, LLC.
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

package com.morpheusdata.nutanix.prism.plugin.flow

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.providers.NetworkProvider
import com.morpheusdata.core.providers.CloudInitializationProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.AccountIntegrationType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkRouterType
import com.morpheusdata.model.NetworkServer
import com.morpheusdata.model.NetworkServerType
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.SecurityGroup
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.sync.flow.*
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.Renderer
import com.nutanix.dp1.mic.microseg.v4.config.NetworkSecurityPolicy
import com.nutanix.dp1.mic.microseg.v4.config.SecurityPolicyState
import com.nutanix.dp1.mic.microseg.v4.config.SecurityPolicyType
import com.nutanix.dp1.net.networking.v4.config.Subnet
import com.nutanix.dp1.net.networking.v4.config.SubnetType
import com.nutanix.mic.java.client.ApiClient
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismNetworkProvider implements NetworkProvider, CloudInitializationProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext

	final String code = 'nutanix-prism-network-provider'
	final String name = 'Nutanix Flow'
	final String description = 'Nutanix Prism Central Flow'

	NutanixPrismNetworkProvider(NutanixPrismPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	String getNetworkServerTypeCode() {
		return 'nutanix-prism-network-provider'
	}

	@Override
	Boolean getCreatable() {
		return true
	}

	@Override
	ServiceResponse refresh(NetworkServer networkServer) {

		Cloud cloud = morpheusContext.services.cloud.get(networkServer.zoneId)
		def authConfig = plugin.getAuthConfig(cloud)

		ApiClient nutanixClient = new ApiClient()
		URL url = new URL(authConfig.apiUrl as String)
		nutanixClient.setHost(url.host)
		nutanixClient.setPort(url.port)
		nutanixClient.setUsername(authConfig.username as String)
		nutanixClient.setPassword(authConfig.password as String)
		nutanixClient.setVerifySsl(false)
		//sync service groups - NetworkResourceGroup
		(new ServiceGroupsSync(this.plugin, cloud, networkServer, nutanixClient)).execute()

		//sync address groups - NetworkResourceGroup
		(new AddressGroupsSync(this.plugin, cloud, networkServer, nutanixClient)).execute()

		//sync security policies - SecurityGroup+SecurityGroupLocation
		(new SecurityPoliciesSync(this.plugin, cloud, networkServer, nutanixClient)).execute()

		return ServiceResponse.success()
	}

	@Override
	String getCloudProviderCode() {
		return 'nutanix-prism-cloud'
	}

	@Override
	Boolean isUserVisible() {
		return true
	}

	/**
	 * Provides a Collection of NetworkTypes that can be managed by this provider
	 * @return Collection of NetworkType
	 */
	@Override
	Collection<NetworkType> getNetworkTypes() {

		def networkCluster =  new OptionType([
				name : 'cluster',
				code : 'nutanix-prism-network-cluster',
				fieldName : 'clusterName',
				noBlank: true,
				fieldContext : 'config',
				fieldLabel : 'Cluster',
				required : true,
				inputType : OptionType.InputType.SELECT,
				displayOrder : 100,
				optionSource: 'nutanixPrismCluster',
		])
		def networkVPC = new OptionType([
				name : 'vpc',
				code : 'nutanix-prism-network-vpc',
				fieldName : 'vpcId',
				fieldContext : 'config',
				fieldLabel : 'VPC',
				required : false,
				inputType : OptionType.InputType.SELECT,
				displayOrder : 100,
				optionSource: 'nutanixPrismVPC',

		])

		NetworkType vlanNetwork = new NetworkType([
			code              : 'nutanix-prism-vlan-network',
			externalType      : 'VLAN',
			cidrEditable      : true,
			dhcpServerEditable: true,
			dnsEditable       : true,
			gatewayEditable   : true,
			creatable         : true,
			deletable         : true,
			hasCidr           : true,
			vlanIdEditable    : true,
			canAssignPool     : true,
			name              : 'Nutanix Prism Central Managed VLAN Network',
			optionTypes       : [networkCluster]
		])
		NetworkType overlayNetwork = new NetworkType([
			code              : 'nutanix-prism-overlay-network',
			externalType      : 'OVERLAY',
			cidrEditable      : true,
			dhcpServerEditable: true,
			dnsEditable       : true,
			gatewayEditable   : true,
			vlanIdEditable    : true,
			creatable         : true,
			deletable         : true,
			canAssignPool     : true,
			name              : 'Nutanix Prism Central Overlay Network',
			optionTypes       : [networkVPC]
		])
		NetworkType unmanagedVlanNetwork = new NetworkType([
			code              : 'nutanix-prism-unmanaged-vlan-network',
			externalType      : 'VLAN',
			cidrEditable      : true,
			dhcpServerEditable: true,
			dnsEditable       : true,
			gatewayEditable   : true,
			vlanIdEditable    : true,
			creatable         : true,
			deletable         : true,
			canAssignPool     : true,
			name              : 'Nutanix Prism Central VLAN Network',
			optionTypes       : [networkCluster]
		])
		[vlanNetwork, overlayNetwork, unmanagedVlanNetwork]
	}

	@Override
	Collection<NetworkRouterType> getRouterTypes() {
		return []
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		return [
		    new OptionType(
				name: 'zoneId',
				category: 'networkServerType.nutanix.prism',
				fieldName: 'zoneId',
				fieldLabel: 'Nutanix Prism Central Cloud',
				fieldContext: 'domain',
				required: true,
				editable: true,
				inputType: OptionType.InputType.SELECT,
				fieldCode: 'gomorpheus.label.cloud',
				displayOrder: 10,
				optionSource: 'clouds'
			)
		]
	}

	@Override
	Collection<OptionType> getSecurityGroupOptionTypes() {
		return []
	}

	@Override
	ServiceResponse initializeProvider(Cloud cloud) {
		log.info("Initializing network provider for ${cloud.name}")
		ServiceResponse rtn = ServiceResponse.prepare()
		def authConfig = plugin.getAuthConfig(cloud)
		try {

			AccountIntegration accountIntegration = new AccountIntegration(
				name: cloud.name + ' Flow',
				integrationType: new AccountIntegrationType(code: 'nutanix-prism-flow'),
				serviceUrl: authConfig.apiUrl,
				serviceUsername: authConfig.username,
				servicePassword: authConfig.password,
			)
			ServiceResponse<AccountIntegration> accountIntegrationResponse = morpheusContext.services.integration.registerCloudIntegration(cloud.id, accountIntegration) as ServiceResponse<AccountIntegration>
			AccountIntegration savedIntegration = accountIntegrationResponse.data
			log.debug("initializeProvider savedIntegration: {}", savedIntegration)
			NetworkServer networkServer = new NetworkServer(
				name: cloud.name,
				type: new NetworkServerType(code:getNetworkServerTypeCode()),
				integration: new AccountIntegration(id: savedIntegration.id)
			)
			morpheusContext.services.integration.registerCloudIntegration(cloud.id, networkServer)
			rtn.success = true
		} catch (Exception e) {
			rtn.success = false
			log.error("initializeProvider error: {}", e, e)
		}

		return rtn
	}

	@Override
	ServiceResponse deleteProvider(Cloud cloud) {
		log.info("Deleting network provider for ${cloud.name}")
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			// cleanup is done by type, so we do not need to load the record
			NetworkServer networkServer = new NetworkServer(
				name: cloud.name,
				type: new NetworkServerType(code:getNetworkServerTypeCode())
			)
			morpheusContext.services.integration.deleteCloudIntegration(cloud.id, networkServer)
			rtn.success = true
		} catch (Exception e) {
			rtn.success = false
			log.error("deleteProvider error: {}", e, e)
		}

		return rtn
	}

	@Override
	ServiceResponse<Network> prepareNetwork(Network network, Map opts) {
		return ServiceResponse.success(network)
	}

	@Override
	ServiceResponse validateNetwork(Network network, Map opts) {
		def errors = [:]
		if (!network.name) {
			errors.name = 'Network name is required'
		}
		if (network.type?.code == 'nutanix-prism-vlan-network' && network.vlanId == null) {
			errors.vlanId = 'VLAN ID is required for managed VLAN networks'
		}
		if (network.type?.code == 'nutanix-prism-overlay-network' && !network.cloudPool?.externalId) {
			errors.cloudPool = 'A VPC is required for overlay networks'
		}
		return errors ? ServiceResponse.error('Validation failed', null, errors) : ServiceResponse.success()
	}

	@Override
	ServiceResponse createNetwork(Network network, Map opts) {
		log.debug("createNetwork: {}", network.name)
		try {
			Cloud cloud = morpheusContext.services.cloud.get(network.zoneId)
			def authConfig = plugin.getAuthConfig(cloud)
			def netClient = buildNetApiClient(authConfig)
			def prismClient = buildPrismApiClient(authConfig)

			Subnet subnet = buildSubnet(network)
			ServiceResponse createResponse = NutanixPrismComputeUtility.createSubnet(netClient, subnet)
			if (!createResponse.success) {
				return createResponse
			}
			ServiceResponse taskResponse = NutanixPrismComputeUtility.waitForTask(prismClient, createResponse.data as String)
			if (!taskResponse.success) {
				return taskResponse
			}
			network.externalId = taskResponse.data as String
			return ServiceResponse.success(network)
		} catch (Exception e) {
			log.error("createNetwork error: ${e.message}", e)
			return ServiceResponse.error("Error creating network: ${e.message}")
		}
	}

	@Override
	ServiceResponse<Network> updateNetwork(Network network, Map opts) {
		log.debug("updateNetwork: {}", network.externalId)
		try {
			Cloud cloud = morpheusContext.services.cloud.get(network.zoneId)
			def authConfig = plugin.getAuthConfig(cloud)
			def netClient = buildNetApiClient(authConfig)
			def prismClient = buildPrismApiClient(authConfig)

			Subnet subnet = buildSubnet(network)
			ServiceResponse updateResponse = NutanixPrismComputeUtility.updateSubnet(netClient, network.externalId, subnet)
			if (!updateResponse.success) {
				return updateResponse
			}
			ServiceResponse taskResponse = NutanixPrismComputeUtility.waitForTask(prismClient, updateResponse.data as String)
			if (!taskResponse.success) {
				return taskResponse
			}
			return ServiceResponse.success(network)
		} catch (Exception e) {
			log.error("updateNetwork error: ${e.message}", e)
			return ServiceResponse.error("Error updating network: ${e.message}")
		}
	}

	@Override
	ServiceResponse deleteNetwork(Network network, Map opts) {
		log.debug("deleteNetwork: {}", network.externalId)
		if (!network.externalId) {
			return ServiceResponse.success()
		}
		try {
			Cloud cloud = morpheusContext.services.cloud.get(network.zoneId)
			def authConfig = plugin.getAuthConfig(cloud)
			def netClient = buildNetApiClient(authConfig)
			def prismClient = buildPrismApiClient(authConfig)

			ServiceResponse deleteResponse = NutanixPrismComputeUtility.deleteSubnet(netClient, network.externalId)
			if (!deleteResponse.success) {
				return deleteResponse
			}
			return NutanixPrismComputeUtility.waitForTask(prismClient, deleteResponse.data as String)
		} catch (Exception e) {
			log.error("deleteNetwork error: ${e.message}", e)
			return ServiceResponse.error("Error deleting network: ${e.message}")
		}
	}

	private Subnet buildSubnet(Network network) {
		Subnet subnet = new Subnet()
		subnet.name = network.name
		subnet.description = network.description

		switch (network.type?.externalType) {
			case 'OVERLAY':
				subnet.subnetType = SubnetType.OVERLAY
				if (network.cloudPool?.externalId) {
					subnet.vpcReference = network.cloudPool.externalId
				}
				break
			default: // VLAN and unmanaged-VLAN both map to VLAN
				subnet.subnetType = SubnetType.VLAN
				if (network.vlanId != null) {
					subnet.networkId = network.vlanId
				}
				if (network.zonePoolId) {
					subnet.clusterReference = network.zonePoolId as String
				}
		}
		subnet
	}

	private com.nutanix.net.java.client.ApiClient buildNetApiClient(Map authConfig) {
		URL url = new URL(authConfig.apiUrl as String)
		new com.nutanix.net.java.client.ApiClient()
			.setHost(url.host)
			.setPort(url.port)
			.setUsername(authConfig.username as String)
			.setPassword(authConfig.password as String)
			.setVerifySsl(false)
	}

	private com.nutanix.pri.java.client.ApiClient buildPrismApiClient(Map authConfig) {
		URL url = new URL(authConfig.apiUrl as String)
		new com.nutanix.pri.java.client.ApiClient()
			.setHost(url.host)
			.setPort(url.port)
			.setUsername(authConfig.username as String)
			.setPassword(authConfig.password as String)
			.setVerifySsl(false)
	}

	private ApiClient buildMicApiClient(Map authConfig) {
		URL url = new URL(authConfig.apiUrl as String)
		new ApiClient()
			.setHost(url.host)
			.setPort(url.port)
			.setUsername(authConfig.username as String)
			.setPassword(authConfig.password as String)
			.setVerifySsl(false)
	}

	@Override
	ServiceResponse<SecurityGroupLocation> createSecurityGroup(SecurityGroup sg, Map opts) {
		log.debug("createSecurityGroup: {}", sg.name)
		try {
			NetworkServer ns = morpheusContext.services.network.server.get(sg.networkServerId)
			Cloud cloud = morpheusContext.services.cloud.get(ns.zoneId)
			def authConfig = plugin.getAuthConfig(cloud)
			def micClient = buildMicApiClient(authConfig)
			def prismClient = buildPrismApiClient(authConfig)

			NetworkSecurityPolicy policy = buildSecurityPolicy(sg)
			ServiceResponse createResp = NutanixPrismComputeUtility.createSecurityPolicy(micClient, policy)
			if (!createResp.success) return createResp

			ServiceResponse taskResp = NutanixPrismComputeUtility.waitForTask(prismClient, createResp.data as String)
			if (!taskResp.success) return taskResp

			SecurityGroupLocation location = new SecurityGroupLocation(
				securityGroup: sg,
				externalId: taskResp.data as String,
				externalType: 'SecurityPolicy'
			)
			return ServiceResponse.success(location)
		} catch (Exception e) {
			log.error("createSecurityGroup error: ${e.message}", e)
			return ServiceResponse.error("Error creating security policy: ${e.message}")
		}
	}

	@Override
	ServiceResponse<SecurityGroup> updateSecurityGroup(SecurityGroup sg, Map opts) {
		log.debug("updateSecurityGroup: {}", sg.externalId)
		if (!sg.externalId) return ServiceResponse.error("SecurityGroup has no externalId")
		try {
			NetworkServer ns = morpheusContext.services.network.server.get(sg.networkServerId)
			Cloud cloud = morpheusContext.services.cloud.get(ns.zoneId)
			def authConfig = plugin.getAuthConfig(cloud)
			def micClient = buildMicApiClient(authConfig)
			def prismClient = buildPrismApiClient(authConfig)

			NetworkSecurityPolicy policy = buildSecurityPolicy(sg)
			ServiceResponse updateResp = NutanixPrismComputeUtility.updateSecurityPolicy(micClient, sg.externalId, policy)
			if (!updateResp.success) return updateResp

			ServiceResponse taskResp = NutanixPrismComputeUtility.waitForTask(prismClient, updateResp.data as String)
			if (!taskResp.success) return taskResp

			return ServiceResponse.success(sg)
		} catch (Exception e) {
			log.error("updateSecurityGroup error: ${e.message}", e)
			return ServiceResponse.error("Error updating security policy: ${e.message}")
		}
	}

	@Override
	ServiceResponse deleteSecurityGroup(SecurityGroup sg) {
		log.debug("deleteSecurityGroup: {}", sg.externalId)
		if (!sg.externalId) return ServiceResponse.success()
		try {
			NetworkServer ns = morpheusContext.services.network.server.get(sg.networkServerId)
			Cloud cloud = morpheusContext.services.cloud.get(ns.zoneId)
			def authConfig = plugin.getAuthConfig(cloud)
			def micClient = buildMicApiClient(authConfig)
			def prismClient = buildPrismApiClient(authConfig)

			ServiceResponse deleteResp = NutanixPrismComputeUtility.deleteSecurityPolicy(micClient, sg.externalId)
			if (!deleteResp.success) return deleteResp

			return NutanixPrismComputeUtility.waitForTask(prismClient, deleteResp.data as String)
		} catch (Exception e) {
			log.error("deleteSecurityGroup error: ${e.message}", e)
			return ServiceResponse.error("Error deleting security policy: ${e.message}")
		}
	}

	private NetworkSecurityPolicy buildSecurityPolicy(SecurityGroup sg) {
		NetworkSecurityPolicy policy = new NetworkSecurityPolicy()
		policy.name = sg.name
		policy.description = sg.description
		if (sg.groupLayer) {
			try {
				policy.type = SecurityPolicyType.valueOf(sg.groupLayer.toUpperCase())
			} catch (IllegalArgumentException ignored) {
				policy.type = SecurityPolicyType.APPLICATION
			}
		} else {
			policy.type = SecurityPolicyType.APPLICATION
		}
		policy.state = SecurityPolicyState.SAVE
		policy
	}

	@Override
	ServiceResponse<NetworkSubnet> createSubnet(NetworkSubnet networkSubnet, Network network, Map map) {
		return ServiceResponse.success(networkSubnet)
	}

	@Override
	ServiceResponse<NetworkSubnet> updateSubnet(NetworkSubnet networkSubnet, Network network, Map map) {
		return ServiceResponse.success(networkSubnet)
	}

	@Override
	ServiceResponse deleteSubnet(NetworkSubnet networkSubnet, Network network, Map map) {
		return ServiceResponse.success()
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	@Override
	Renderer<?> getRenderer() {
		return null
	}
}
