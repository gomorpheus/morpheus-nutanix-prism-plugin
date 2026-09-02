package com.morpheusdata.nutanix.prism.plugin.utils

import com.morpheusdata.core.util.ComputeUtility
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

	void "getTaskV4 calls the prism V4 tasks endpoint and normalizes status/entities to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.getTaskV4(client, authConfig, 'task-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/prism/v4.0/config/tasks/task-1', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [
						status          : 'SUCCEEDED',
						entitiesAffected: [[extId: 'image-1', rel: 'vmm:content:image']]
				]
		])
		result.success
		result.data.status == 'SUCCEEDED'
		result.data.entity_reference_list.size() == 1
		result.data.entity_reference_list[0].kind == 'image'
		result.data.entity_reference_list[0].uuid == 'image-1'
	}

	void "listImagesV4 calls the vmm V4 content images endpoint and normalizes to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listImagesV4(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/images', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data    : [[
						extId                 : 'image-1',
						name                  : 'CentOS Image',
						type                  : 'DISK_IMAGE',
						sizeBytes             : 1073741824,
						source                : [url: 'https://files.example.com/centos.qcow2'],
						clusterLocationExtIds : ['cluster-1']
				]],
				metadata: [totalAvailableResults: 1]
		])
		result.success
		result.data.size() == 1
		def image = result.data[0]
		image.metadata.uuid == 'image-1'
		image.status.name == 'CentOS Image'
		image.status.resources.image_type == 'DISK_IMAGE'
		image.status.resources.size_bytes == 1073741824
		image.status.resources.retrieval_uri_list == ['https://files.example.com/centos.qcow2']
		image.status.resources.source_uri == 'https://files.example.com/centos.qcow2'
		image.status.resources.current_cluster_reference_list[0].uuid == 'cluster-1'
	}

	void "getImageV4 calls the vmm V4 content images endpoint for a single image and normalizes to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.getImageV4(client, authConfig, 'image-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/images/image-1', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [extId: 'image-1', name: 'CentOS Image', type: 'DISK_IMAGE', sizeBytes: 1073741824]
		])
		result.success
		result.data.metadata.uuid == 'image-1'
		result.data.status.name == 'CentOS Image'
		result.data.status.resources.image_type == 'DISK_IMAGE'
	}

	void "createImageV4 posts a UrlSource body when given a sourceUri and normalizes the task reference"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.createImageV4(client, authConfig, 'CentOS Image', 'DISK_IMAGE', 'https://files.example.com/centos.qcow2')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/images', authConfig.username, authConfig.password, {
			it.body.name == 'CentOS Image' &&
			it.body.type == 'DISK_IMAGE' &&
			it.body.source['$objectType'] == 'vmm.v4.content.UrlSource' &&
			it.body.source.url == 'https://files.example.com/centos.qcow2'
		}, 'POST') >> ServiceResponse.success([data: [extId: 'task-1']])
		result.success
		result.data.status.execution_context.task_uuid == 'task-1'
	}

	void "createImageV4 posts a VmDiskSource body when given a diskUuid"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.createImageV4(client, authConfig, 'Clone Image', 'DISK_IMAGE', null, 'disk-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/images', authConfig.username, authConfig.password, {
			it.body.source['$objectType'] == 'vmm.v4.content.VmDiskSource' &&
			it.body.source.extId == 'disk-1'
		}, 'POST') >> ServiceResponse.success([data: [extId: 'task-2']])
		result.success
		result.data.status.execution_context.task_uuid == 'task-2'
	}

	void "deleteImageV4 calls DELETE on the vmm V4 content images endpoint and normalizes the task reference"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.deleteImageV4(client, authConfig, 'image-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/images/image-1', authConfig.username, authConfig.password, _, 'DELETE') >> ServiceResponse.success([data: [extId: 'task-3']])
		result.success
		result.data.status.execution_context.task_uuid == 'task-3'
	}

	void "listTemplates calls the vmm V4 content templates endpoint and fetches full vmSpec per template on the stable API version"() {
		given:
		def client = Mock(HttpApiClient)
		authConfig.vmmApiVersion = NutanixPrismComputeUtility.VMM_API_VERSION.V4_0

		when:
		def result = NutanixPrismComputeUtility.listTemplates(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/templates', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [[extId: 'template-1', templateName: 'CentOS Template', templateVersionSpec: [vmSpec: null]]]
		])
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/templates/template-1', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [extId: 'template-1', templateVersionSpec: [vmSpec: [disks: [[extId: 'disk-1', diskAddress: [busType: 'SCSI', index: 0], backingInfo: [diskSizeBytes: 1073741824, storageContainer: [extId: 'container-1']]]]]]]
		])
		result.success
		result.data.data[0].templateName == 'CentOS Template'
		result.data.data[0].templateVersionSpec.vmSpec.disk_list.size() == 1
		result.data.data[0].templateVersionSpec.vmSpec.disk_list[0].disk_size_bytes == 1073741824
		result.data.data[0].templateVersionSpec.vmSpec.disk_list[0].storage_config.storage_container_reference.uuid == 'container-1'
	}

	void "listTemplates calls the preview vmm V4 templates endpoint and expands vmSpec inline on the a1 API version"() {
		given:
		def client = Mock(HttpApiClient)
		authConfig.vmmApiVersion = NutanixPrismComputeUtility.VMM_API_VERSION.V4_0_A1

		when:
		def result = NutanixPrismComputeUtility.listTemplates(client, authConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0.a1/templates', authConfig.username, authConfig.password, { it.queryParams['$expand'] == 'vmSpec' }, 'GET') >> ServiceResponse.success([
				data: [[extId: 'template-1', templateName: 'CentOS Template', templateVersionSpec: [vmSpec: '{"spec":{"resources":{"disk_list":[{"extId":"disk-1"}]}}}']]]
		])
		result.success
		result.data.data[0].templateVersionSpec.vmSpec.disk_list.size() == 1
		result.data.data[0].templateVersionSpec.vmSpec.disk_list[0].extId == 'disk-1'
	}

	void "getTemplate calls the vmm V4 content templates endpoint for a single template and normalizes the vmSpec disk list"() {
		given:
		def client = Mock(HttpApiClient)
		authConfig.vmmApiVersion = NutanixPrismComputeUtility.VMM_API_VERSION.V4_0

		when:
		def result = NutanixPrismComputeUtility.getTemplate(client, authConfig, 'template-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/templates/template-1', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [extId: 'template-1', templateVersionSpec: [vmSpec: [disks: [[extId: 'disk-1', diskAddress: [busType: 'SCSI', index: 0], backingInfo: [diskSizeBytes: 2147483648, storageContainer: [extId: 'container-1']]]]]]]
		])
		result.success
		result.data.data.templateVersionSpec.vmSpec.disk_list[0].disk_size_bytes == 2147483648
	}

	void "createVmFromTemplate posts a deploy action to the vmm V4 content templates endpoint"() {
		given:
		def client = Mock(HttpApiClient)
		authConfig.vmmApiVersion = NutanixPrismComputeUtility.VMM_API_VERSION.V4_0
		def runConfig = [
				imageExternalId : 'template-1',
				clusterReference: [uuid: 'cluster-1'],
				name            : 'new-vm',
				numSockets      : 1,
				coresPerSocket  : 2,
				maxMemory       : 4096l * ComputeUtility.ONE_MEGABYTE,
				nicList         : []
		]

		when:
		def result = NutanixPrismComputeUtility.createVmFromTemplate(client, authConfig, runConfig)

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/content/templates/template-1/$actions/deploy', authConfig.username, authConfig.password, {
			it.body.clusterReference == 'cluster-1' &&
			it.body.vmName == 'new-vm' &&
			it.body.overrideVmConfigMap['0'].numSockets == 1
		}, 'POST') >> ServiceResponse.success([data: [extId: 'task-1']])
		result.success
	}

	void "createCategoryV4 posts a combined key/value body to the prism V4 categories endpoint"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.createCategoryV4(client, authConfig, 'Environment', 'Production')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/prism/v4.0/config/categories', authConfig.username, authConfig.password, {
			it.body.key == 'Environment' && it.body.value == 'Production'
		}, 'POST') >> ServiceResponse.success([data: [extId: 'cat-1', key: 'Environment', value: 'Production']])
		result.success
		result.data.key == 'Environment'
		result.data.value == 'Production'
	}

	void "getVmStatsV4 calls the vmm V4 ahv stats endpoint for a single VM with a required time range"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.getVmStatsV4(client, authConfig, 'vm-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/ahv/stats/vms/vm-1', authConfig.username, authConfig.password, {
			it.queryParams['$statType'] == 'LAST' && it.queryParams['$startTime'] != null && it.queryParams['$endTime'] != null
		}, 'GET') >> ServiceResponse.success([data: [vmExtId: 'vm-1', stats: [[timestamp: '2024-01-01T00:00:00Z', memoryUsagePpm: 100000, hypervisorCpuUsagePpm: 50000, controllerUserBytes: 1024]]]])
		result.success
		result.data.stats[0].memoryUsagePpm == 100000
	}

	void "listVMMetricsV4 calls getVmStatsV4 per VM and normalizes to the V3 Groups API shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listVMMetricsV4(client, authConfig, ['vm-1', 'vm-2'])

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/ahv/stats/vms/vm-1', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [vmExtId: 'vm-1', stats: [[memoryUsagePpm: 100000, hypervisorCpuUsagePpm: 50000, controllerUserBytes: 1024]]]
		])
		1 * client.callJsonApi(authConfig.apiUrl, 'api/vmm/v4.0/ahv/stats/vms/vm-2', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [vmExtId: 'vm-2', stats: [[memoryUsagePpm: 200000, hypervisorCpuUsagePpm: 60000, controllerUserBytes: 2048]]]
		])
		result.success
		result.data.size() == 2
		def vm1 = result.data.find { it.entity_id == 'vm-1' }
		NutanixPrismComputeUtility.getGroupEntityValue(vm1.data, 'memory_usage_ppm') == 100000
		NutanixPrismComputeUtility.getGroupEntityValue(vm1.data, 'hypervisor_cpu_usage_ppm') == 50000
		NutanixPrismComputeUtility.getGroupEntityValue(vm1.data, 'controller_user_bytes') == 1024
		def vm2 = result.data.find { it.entity_id == 'vm-2' }
		NutanixPrismComputeUtility.getGroupEntityValue(vm2.data, 'memory_usage_ppm') == 200000
	}

	void "listSnapshotsV4 calls the dataprotection V4 recovery-points endpoint filtered by cluster and normalizes to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.listSnapshotsV4(client, authConfig, 'cluster-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/dataprotection/v4.4/config/recovery-points', authConfig.username, authConfig.password, {
			it.queryParams['$filter'] == "sourceLocation/clusterExtIds/any(a:a eq 'cluster-1')"
		}, 'GET') >> ServiceResponse.success([
				data    : [[
						extId            : 'rp-1',
						name             : 'server1.123456',
						creationTime     : '2024-01-15T10:30:00Z',
						vmRecoveryPoints : [[vmExtId: 'vm-1']]
				]],
				metadata: [totalAvailableResults: 1]
		])
		result.success
		result.data.size() == 1
		def snapshot = result.data[0]
		snapshot.uuid == 'rp-1'
		snapshot.snapshot_name == 'server1.123456'
		snapshot.vm_uuid == 'vm-1'
		snapshot.created_time == 1705314600000L * 1000
	}

	void "getSnapshotV4 calls the dataprotection V4 recovery-points endpoint for a single snapshot and normalizes to the V3 shape"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.getSnapshotV4(client, authConfig, 'cluster-1', 'rp-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/dataprotection/v4.4/config/recovery-points/rp-1', authConfig.username, authConfig.password, _, 'GET') >> ServiceResponse.success([
				data: [extId: 'rp-1', name: 'server1.123456', creationTime: '2024-01-15T10:30:00Z', vmRecoveryPoints: [[vmExtId: 'vm-1']]]
		])
		result.success
		result.data.uuid == 'rp-1'
		result.data.snapshot_name == 'server1.123456'
		result.data.vm_uuid == 'vm-1'
	}

	void "createSnapshotV4 posts a crash-consistent recovery point body and normalizes the task reference"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.createSnapshotV4(client, authConfig, 'cluster-1', 'vm-1', 'server1.123456')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/dataprotection/v4.4/config/recovery-points', authConfig.username, authConfig.password, {
			it.body.name == 'server1.123456' &&
			it.body.recoveryPointType == 'CRASH_CONSISTENT' &&
			it.body.vmRecoveryPoints == [[vmExtId: 'vm-1']]
		}, 'POST') >> ServiceResponse.success([data: [extId: 'task-1']])
		result.success
		result.data.task_uuid == 'task-1'
	}

	void "deleteSnapshotV4 calls DELETE on the dataprotection V4 recovery-points endpoint and normalizes the task reference"() {
		given:
		def client = Mock(HttpApiClient)

		when:
		def result = NutanixPrismComputeUtility.deleteSnapshotV4(client, authConfig, 'cluster-1', 'rp-1')

		then:
		1 * client.callJsonApi(authConfig.apiUrl, 'api/dataprotection/v4.4/config/recovery-points/rp-1', authConfig.username, authConfig.password, _, 'DELETE') >> ServiceResponse.success([data: [extId: 'task-2']])
		result.success
		result.data.task_uuid == 'task-2'
	}
}
