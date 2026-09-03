package com.morpheusdata.nutanix.prism.plugin.utils

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class NutanixPrismV4ClientSpec extends Specification {

	@Subject
	Class subject = NutanixPrismV4Client

	Map authConfig

	void setup() {
		authConfig = [apiUrl: 'https://10.0.0.1:9440', username: 'admin', password: 'password', ignoreSSL: true, timeout: 30000]
	}

	@Unroll
	void "build#domain V4Path prefixes the correct base path"() {
		expect:
		buildMethod.call('clusters') == expectedPath

		where:
		domain         | buildMethod                                                          | expectedPath
		'Networking'   | { String p -> NutanixPrismV4Client.buildNetworkingV4Path(p) }         | 'api/networking/v4.0/config/clusters'
		'Microseg'     | { String p -> NutanixPrismV4Client.buildMicrosegV4Path(p) }           | 'api/microseg/v4.0/config/clusters'
		'Prism'        | { String p -> NutanixPrismV4Client.buildPrismV4Path(p) }              | 'api/prism/v4.0/config/clusters'
		'ClusterMgmt'  | { String p -> NutanixPrismV4Client.buildClusterMgmtV4Path(p) }         | 'api/clustermgmt/v4.0/config/clusters'
	}

	void "buildVmmV4Path is hardcoded to v4.3 regardless of authConfig"() {
		expect:
		NutanixPrismV4Client.buildVmmV4Path('vms') == 'api/vmm/v4.3/ahv/config/vms'
	}

	void "buildV4Headers returns JSON content type"() {
		expect:
		NutanixPrismV4Client.buildV4Headers() == ['Content-Type': 'application/json']
	}

	void "callListApiV4 returns success with a single page of results"() {
		given:
		def client = Mock(HttpApiClient)
		def path = 'api/prism/v4.0/config/clusters'

		when:
		def result = NutanixPrismV4Client.callListApiV4(client, path, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, path, authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data    : [[extId: 'cluster-1'], [extId: 'cluster-2']],
				metadata: [totalAvailableResults: 2]
		])
		result.success
		result.data.size() == 2
		result.data*.extId == ['cluster-1', 'cluster-2']
	}

	void "callListApiV4 pages until totalAvailableResults is reached"() {
		given:
		def client = Mock(HttpApiClient)
		def path = 'api/prism/v4.0/config/clusters'

		when:
		def result = NutanixPrismV4Client.callListApiV4(client, path, authConfig, [:], 1)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, path, authConfig.username, authConfig.password, { it.queryParams['$page'] == '0' }, 'GET') >> ServiceResponse.success([
				data    : [[extId: 'cluster-1']],
				metadata: [totalAvailableResults: 2]
		])
		1 * client.callJsonApi(authConfig.apiUrl, path, authConfig.username, authConfig.password, { it.queryParams['$page'] == '1' }, 'GET') >> ServiceResponse.success([
				data    : [[extId: 'cluster-2']],
				metadata: [totalAvailableResults: 2]
		])
		result.success
		result.data.size() == 2
		result.data*.extId == ['cluster-1', 'cluster-2']
	}

	void "callListApiV4 stops paging when a page returns no data"() {
		given:
		def client = Mock(HttpApiClient)
		def path = 'api/prism/v4.0/config/clusters'

		when:
		def result = NutanixPrismV4Client.callListApiV4(client, path, authConfig)

		then:
		1 * client.callJsonApi(*_) >> ServiceResponse.success([data: [], metadata: [totalAvailableResults: 0]])
		result.success
		result.data == []
	}

	void "callListApiV4 returns an error response and flags invalid login on 401"() {
		given:
		def client = Mock(HttpApiClient)
		def path = 'api/prism/v4.0/config/clusters'

		when:
		def result = NutanixPrismV4Client.callListApiV4(client, path, authConfig)

		then:
		1 * client.callJsonApi(*_) >> {
			def response = ServiceResponse.error('unauthorized', null, [message: 'unauthorized'])
			response.setErrorCode('401')
			return response
		}
		!result.success
		result.data.invalidLogin == true
	}
}
