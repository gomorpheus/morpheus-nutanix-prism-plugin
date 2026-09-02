package com.morpheusdata.nutanix.prism.plugin.utils

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject

class NutanixPrismComputeUtilitySpec extends Specification {

	@Subject
	Class subject = NutanixPrismComputeUtility

	Map authConfig

	void setup() {
		authConfig = [apiUrl: 'https://10.0.0.1:9440', username: 'admin', password: 'password', ignoreSSL: true, timeout: 30000]
	}

	void "listCategoriesV4 calls the Prism V4 categories endpoint and returns key/value entries"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listCategoriesV4(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/prism/v4.0/config/categories', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data    : [[extId: 'cat-1', key: 'Environment', value: 'Production'], [extId: 'cat-2', key: 'Environment', value: 'Staging']],
				metadata: [totalAvailableResults: 2]
		])
		result.success
		result.data.size() == 2
		result.data*.key == ['Environment', 'Environment']
		result.data*.value == ['Production', 'Staging']
	}

	void "listClustersV4 calls the clustermgmt V4 clusters endpoint and normalizes to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listClustersV4(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/clustermgmt/v4.0/config/clusters', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data    : [[
						extId : 'cluster-1',
						name  : 'Cluster One',
						config: [
								clusterFunction   : ['AOS'],
								clusterSoftwareMap: [[softwareType: 'NOS', version: '6.5.2'], [softwareType: 'NCC', version: '4.6.0']]
						]
				]],
				metadata: [totalAvailableResults: 1]
		])
		result.success
		result.data.size() == 1
		result.data[0].metadata.uuid == 'cluster-1'
		result.data[0].status.name == 'Cluster One'
		result.data[0].status.resources.config.service_list == ['AOS']
		result.data[0].status.resources.config.software_map.NOS.version == '6.5.2'
		result.data[0].status.resources.config.software_map.NCC.version == '4.6.0'
	}

	void "listHostsV4 calls the clustermgmt V4 hosts endpoint, normalizes to the V2 shape, and fetches per-host stats"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listHostsV4(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/clustermgmt/v4.0/config/hosts', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data    : [[
						extId               : 'host-1',
						hostName            : 'Host One',
						cluster             : [uuid: 'cluster-1', name: 'Cluster One'],
						hypervisor          : [type: 'AHV', externalAddress: [ipv4: [value: '10.0.0.5']]],
						numberOfCpuCores    : 32,
						memorySizeBytes     : 137438953472
				]],
				metadata: [totalAvailableResults: 1]
		])
		1 * client.callJsonApi(authConfig.apiUrl, 'api/clustermgmt/v4.0/stats/clusters/cluster-1/hosts/host-1', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [
						hypervisorCpuUsagePpm            : [[timestamp: '2024-01-01T00:00:00Z', value: 250000]],
						aggregateHypervisorMemoryUsagePpm: [[timestamp: '2024-01-01T00:00:00Z', value: 500000]]
				]
		])
		result.success
		result.data.size() == 1
		def host = result.data[0]
		host.uuid == 'host-1'
		host.cluster_uuid == 'cluster-1'
		host.hypervisor_type == 'kKvm'
		host.name == 'Host One'
		host.hypervisor_address == '10.0.0.5'
		host.num_cpu_cores == 32
		host.memory_capacity_in_bytes == 137438953472
		host.stats.hypervisor_cpu_usage_ppm == 250000
		host.stats.hypervisor_memory_usage_ppm == 500000
	}

	void "listVMsV4 calls the VMM V4 vms endpoint and normalizes to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listVMsV4(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/ahv/config/vms', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data    : [[
						extId            : 'vm-1',
						name             : 'VM One',
						cluster          : [extId: 'cluster-1'],
						powerState       : 'ON',
						memorySizeBytes  : 4294967296,
						numSockets       : 2,
						numCoresPerSocket: 4,
						nics             : [[
								extId      : 'nic-1',
								backingInfo: [macAddress: 'AA:BB:CC:DD:EE:FF'],
								networkInfo: [nicType: 'NORMAL_NIC', subnet: [extId: 'subnet-1'], ipv4Config: [ipAddress: [value: '10.0.0.10']]]
						]],
						disks            : [[
								extId      : 'disk-1',
								diskAddress: [index: 0],
								backingInfo: [diskSizeBytes: 107374182400]
						]]
				]],
				metadata: [totalAvailableResults: 1]
		])
		result.success
		result.data.size() == 1
		def vm = result.data[0]
		vm.metadata.uuid == 'vm-1'
		vm.status.name == 'VM One'
		vm.status.cluster_reference.uuid == 'cluster-1'
		vm.status.resources.power_state == 'ON'
		vm.status.resources.memory_size_mib == 4096
		vm.status.resources.num_sockets == 2
		vm.status.resources.num_vcpus_per_socket == 4
		vm.status.resources.nic_list.size() == 1
		vm.status.resources.nic_list[0].uuid == 'nic-1'
		vm.status.resources.nic_list[0].mac_address == 'AA:BB:CC:DD:EE:FF'
		vm.status.resources.nic_list[0].nic_type == 'NORMAL_NIC'
		vm.status.resources.nic_list[0].subnet_reference.uuid == 'subnet-1'
		vm.status.resources.nic_list[0].ip_endpoint_list[0].ip == '10.0.0.10'
		vm.status.resources.disk_list.size() == 1
		vm.status.resources.disk_list[0].uuid == 'disk-1'
		vm.status.resources.disk_list[0].disk_size_bytes == 107374182400
		vm.status.resources.disk_list[0].device_properties.device_type == 'DISK'
		vm.status.resources.disk_list[0].device_properties.disk_address.device_index == 0
	}

	void "listNetworksV4 calls the networking V4 subnets endpoint and normalizes to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listNetworksV4(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/networking/v4.0/config/subnets', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data    : [[
						extId           : 'subnet-1',
						name            : 'VLAN 100',
						clusterReference: 'cluster-1',
						vpcReference    : 'vpc-1',
						subnetType      : 'VLAN',
						ipConfig        : [[ipv4: [ipSubnet: [ip: [value: '10.0.0.0'], prefixLength: 24]]]]
				]],
				metadata: [totalAvailableResults: 1]
		])
		result.success
		result.data.size() == 1
		def subnet = result.data[0]
		subnet.metadata.uuid == 'subnet-1'
		subnet.status.name == 'VLAN 100'
		subnet.status.cluster_reference.uuid == 'cluster-1'
		subnet.status.resources.subnet_type == 'VLAN'
		subnet.status.resources.ip_config.subnet_ip
		subnet.spec.resources.vpc_reference.uuid == 'vpc-1'
	}

	void "listVPCsV4 calls the networking V4 vpcs endpoint and normalizes to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listVPCsV4(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/networking/v4.0/config/vpcs', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data    : [[extId: 'vpc-1', name: 'VPC One']],
				metadata: [totalAvailableResults: 1]
		])
		result.success
		result.data.size() == 1
		result.data[0].spec.name == 'VPC One'
		result.data[0].metadata.uuid == 'vpc-1'
	}
}
