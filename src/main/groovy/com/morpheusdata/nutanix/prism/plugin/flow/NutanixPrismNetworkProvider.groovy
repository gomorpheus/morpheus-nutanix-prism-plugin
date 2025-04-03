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
import com.morpheusdata.core.providers.SecurityGroupProvider
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
import com.morpheusdata.model.SecurityGroupRule
import com.morpheusdata.model.SecurityGroupRuleLocation
import com.morpheusdata.nutanix.prism.plugin.NutanixPrismPlugin
import com.morpheusdata.nutanix.prism.plugin.utils.NutanixPrismComputeUtility
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.Renderer
import com.nutanix.mic.java.client.ApiClient
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismNetworkProvider implements NetworkProvider, CloudInitializationProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext
	SecurityGroupProvider securityGroupProvider

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
		//def addressGroups = NutanixPrismComputeUtility.listAddressGroups(nutanixClient)
		//def serviceGroups = NutanixPrismComputeUtility.listServiceGroups(nutanixClient)
		def securityPolicies = NutanixPrismComputeUtility.listSecurityPolicies(nutanixClient)


		println "\u001B[33mAC Log - NutanixPrismNetworkProvider:refresh- ${securityPolicies.data[0].extId}\u001B[0m"

		//sync service groups - ref data

		//sync address groups - ref data

		//sync security policies - security groups

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
			//	optionType(meta:[key:'code'], code:'networkServerType.global.serviceUrl', type:'text', name:'serviceUrl',
			//		category:'networkServerType.global', fieldName:'serviceUrl', fieldCode: 'gomorpheus.optiontype.ApiHost', fieldLabel:'API Host', fieldContext:'domain', required:true, enabled:true,
			//		editable:true, global:false, placeHolder:null, helpBlock:'gomorpheus.help.serviceUrl', defaultValue:null, custom:false, displayOrder:9, fieldClass:null,
			//		wrapperClass:null, systemOption: true)
			//
			//	optionType(meta:[key:'code'], code:'networkServerType.global.credential', type:'credential', name:'credentials', optionSource:'credentials', fieldName:'type',
			//		category:'networkServerType.global', fieldCode: 'gomorpheus.label.credential', fieldContext:'credential',
			//		required:false, enabled:true, editable:true, global:false, placeHolder:null, helpBlock:'', defaultValue:'local', custom:false,
			//		displayOrder:12, fieldClass:null, wrapperClass:null, config: JsonOutput.toJson(credentialTypes:['username-password']).toString(), systemOption: true
			//	)
			//	optionType(meta:[key:'code'], code:'networkServerType.global.serviceUsername', type:'text', name:'serviceUsername',
			//		category:'networkServerType.global', fieldName:'serviceUsername', fieldCode: 'gomorpheus.optiontype.Username', fieldLabel:'Username', fieldContext:'domain', required:true, enabled:true,
			//		editable:true, global:false, placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:15, fieldClass:null,
			//		wrapperClass:null, localCredential:true, systemOption: true)
			//	optionType(meta:[key:'code'], code:'networkServerType.global.servicePassword', type:'password', name:'servicePassword',
			//		category:'networkServerType.global', fieldName:'servicePassword', fieldCode: 'gomorpheus.optiontype.Password', fieldLabel:'Password', fieldContext:'domain', required:true, enabled:true,
			//		editable:true, global:false, placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:20, fieldClass:null,
			//		wrapperClass:null, localCredential:true, systemOption: true)
			//optionType(meta:[key:'code'], code:'networkServerType.nsxt.vmwareCloud', type:'select', name:'zoneId', optionSourceType:'nsxt', optionSource:'clouds',
			//		category:'networkServerType.nsx', fieldName:'zoneId', fieldLabel:'Vmware Cloud', fieldContext:'domain', required:true, enabled:true,
			//		editable:true, global:false, placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:60, fieldClass:'nsx-cloud',
			//		wrapperClass:null, fieldCode:'gomorpheus.label.cloud')
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
			println "\u001B[33mAC Log - NutanixPrismNetworkProvider:initializeProvider- ${savedIntegration}\u001B[0m"
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
		println "\u001B[33mAC Log - NutanixPrismNetworkProvider:validateNetwork- ${opts}\u001B[0m"
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse createNetwork(Network network, Map opts) {
		def rtn = ServiceResponse.prepare()
		try {
			if(network.networkServer) {
				Cloud cloud = network.cloud
				println "\u001B[33mAC Log - NutanixPrismNetworkProvider:createNetwork- ${network.dump()} ${opts}\u001B[0m"
				return rtn
				//CloudPool resourcePool = network.cloudPool?.id ? morpheus.cloud.pool.listById([network.cloudPool?.id]).toList().blockingGet()?.getAt(0) : null
				//AmazonEC2Client amazonClient = plugin.getAmazonClient(cloud, false, resourcePool?.regionCode)
				def networkConfig = [:]
				networkConfig.name = network.name
				networkConfig.vpcId = resourcePool?.externalId
				networkConfig.availabilityZone = network.availabilityZone
				networkConfig.active = network.active
				networkConfig.assignPublicIp = network.assignPublicIp
				networkConfig.type = network.type?.externalType
				networkConfig.cidr = network.cidr
				log.debug("sending network config: {}", networkConfig)
				def apiResults = AmazonComputeUtility.createSubnet(opts + [amazonClient: amazonClient, config: networkConfig])
				log.debug("network apiResults: {}", apiResults)
				//create it
				if(apiResults?.success && apiResults?.error != true) {
					rtn.success = true
					network.externalId = apiResults.externalId
					network.uniqueId = apiResults.externalId
					network.regionCode = resourcePool?.regionCode
				}
				rtn.data = network
				rtn.msg = apiResults.msg
				log.debug("results: {}", rtn.results)
			}
		} catch(e) {
			log.error("createNetwork error: ${e}", e)
		}
		return rtn
	}

	/**
	 * Updates the Network submitted
	 * @param network Network information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse<Network> updateNetwork(Network network, Map opts) {
		return ServiceResponse.success(network)
	}

	/**
	 * Deletes the Network submitted
	 * @param network Network information
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteNetwork(Network network, Map opts) {
		log.debug("delete network: {}", network.externalId)
		def rtn = ServiceResponse.prepare()
		//remove the network
		if(network.externalId) {
//			CloudPool resourcePool = network.cloudPool?.id ? morpheus.cloud.pool.listById([network.cloudPool?.id]).toList().blockingGet()?.getAt(0) : null
//			AmazonEC2Client amazonClient = plugin.getAmazonClient(network.cloud, false, resourcePool?.regionCode)
//			def deleteResults = AmazonComputeUtility.deleteSubnet([amazonClient: amazonClient, network: network])
//			log.debug("deleteResults: {}", deleteResults)
//			if(deleteResults.success == true) {
//				rtn.success = true
//			} else if(deleteResults.errorCode == 404) {
//				//not found - success
//				log.warn("not found")
//				rtn.success = true
//			} else {
//				rtn.msg = deleteResults.msg
//			}
		} else {
			rtn.success = true
		}
		return rtn
	}

	@Override
	ServiceResponse<NetworkSubnet> createSubnet(NetworkSubnet networkSubnet, Network network, Map map) {
		return null
	}

	@Override
	ServiceResponse<NetworkSubnet> updateSubnet(NetworkSubnet networkSubnet, Network network, Map map) {
		return null
	}

	@Override
	ServiceResponse deleteSubnet(NetworkSubnet networkSubnet, Network network, Map map) {
		return null
	}

	@Override
	ServiceResponse<SecurityGroup> prepareSecurityGroup(SecurityGroup securityGroup, Map opts) {
		return super.prepareSecurityGroup(securityGroup, opts)
	}

	@Override
	ServiceResponse<SecurityGroupRule> validateSecurityGroupRule(SecurityGroupRule securityGroupRule) {
		return super.validateSecurityGroupRule(securityGroupRule)
	}

	@Override
	ServiceResponse<SecurityGroupRule> prepareSecurityGroupRule(SecurityGroupRule securityGroupRule, Map opts) {
		return super.prepareSecurityGroupRule(securityGroupRule, opts)
	}

	@Override
	ServiceResponse deleteSecurityGroupLocation(SecurityGroupLocation securityGroupLocation) {
		return super.deleteSecurityGroupLocation(securityGroupLocation)
	}

	@Override
	ServiceResponse deleteSecurityGroup(SecurityGroup securityGroup) {
		return super.deleteSecurityGroup(securityGroup)
	}

	@Override
	ServiceResponse<SecurityGroup> updateSecurityGroup(SecurityGroup securityGroup, Map opts) {
		return super.updateSecurityGroup(securityGroup, opts)
	}

	@Override
	ServiceResponse<SecurityGroupLocation> createSecurityGroup(SecurityGroup securityGroup, Map opts) {
		return super.createSecurityGroup(securityGroup, opts)
	}

	@Override
	ServiceResponse validateSecurityGroup(SecurityGroup securityGroup, Map opts) {
		return super.validateSecurityGroup(securityGroup, opts)
	}

	@Override
	ServiceResponse<SecurityGroupRuleLocation> createSecurityGroupRule(SecurityGroupLocation securityGroupLocation, SecurityGroupRule securityGroupRule) {
		return super.createSecurityGroupRule(securityGroupLocation, securityGroupRule)
	}

	@Override
	ServiceResponse<SecurityGroupRule> updateSecurityGroupRule(SecurityGroupLocation securityGroupLocation, SecurityGroupRule originalRule, SecurityGroupRule updatedRule) {
		return super.updateSecurityGroupRule(securityGroupLocation, originalRule, updatedRule)
	}

	@Override
	ServiceResponse deleteSecurityGroupRule(SecurityGroupLocation securityGroupLocation, SecurityGroupRule rule) {
		return super.deleteSecurityGroupRule(securityGroupLocation, rule)
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
