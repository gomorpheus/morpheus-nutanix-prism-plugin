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
}
