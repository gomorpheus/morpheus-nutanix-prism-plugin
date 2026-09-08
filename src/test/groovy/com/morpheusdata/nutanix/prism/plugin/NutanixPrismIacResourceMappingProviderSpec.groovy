package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.MorpheusContext
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class NutanixPrismIacResourceMappingProviderSpec extends Specification {

	@Subject
	NutanixPrismIacResourceMappingProvider service

	MorpheusContext context
	NutanixPrismPlugin plugin

	void setup() {
		context = Mock(MorpheusContext)
		plugin = Mock(NutanixPrismPlugin)
		service = new NutanixPrismIacResourceMappingProvider(plugin, context)
	}

	void "extractIpAddress reads v1 nic_list"() {
		given:
		def values = [nic_list: [[ip_endpoint_list: [[ip: '10.0.0.5']]]]]

		expect:
		service.extractIpAddress(values, false) == '10.0.0.5'
	}

	void "extractIpAddress falls back to v1 nic_list_status when nic_list is empty"() {
		given:
		def values = [nic_list: [], nic_list_status: [[ip_endpoint_list: [[ip: '10.0.0.9']]]]]

		expect:
		service.extractIpAddress(values, false) == '10.0.0.9'
	}

	void "extractIpAddress reads v2 post-2.4.1 nic_network_info shape"() {
		given:
		def values = [
			ext_id: 'vm-ext-id',
			nics  : [[nic_network_info: [virtual_ethernet_nic_network_info: [ipv4_config: [ip_address: [value: '10.1.1.10']]]]]]
		]

		expect:
		service.extractIpAddress(values, true) == '10.1.1.10'
	}

	void "extractIpAddress falls back to v2 pre-2.4.1 deprecated network_info shape"() {
		given:
		def values = [
			ext_id: 'vm-ext-id',
			nics  : [[network_info: [ipv4_config: [ip_address: [value: '10.1.1.20']]]]]
		]

		expect:
		service.extractIpAddress(values, true) == '10.1.1.20'
	}

	void "extractSourceImageId reads v1 disk_list image reference"() {
		given:
		def values = [disk_list: [[data_source_reference: [kind: 'image', uuid: 'image-uuid-v1']]]]

		expect:
		service.extractSourceImageId(values, false) == 'image-uuid-v1'
	}

	void "extractSourceImageId reads v2 disks image reference"() {
		given:
		def values = [
			ext_id: 'vm-ext-id',
			disks : [[backing_info: [vm_disk: [data_source: [reference: [image_reference: [image_ext_id: 'image-ext-id-v2']]]]]]]
		]

		expect:
		service.extractSourceImageId(values, true) == 'image-ext-id-v2'
	}

	@Unroll
	void "resolveV2CategoryTags extracts ext_id from each category reference (categories=#categories)"() {
		expect:
		service.resolveV2CategoryTags(categories) == expected

		where:
		categories                                                        | expected
		null                                                               | []
		[]                                                                 | []
		[[ext_id: 'category-ext-id-1']]                                    | ['category-ext-id-1']
		[[ext_id: 'category-ext-id-1'], [ext_id: 'category-ext-id-2']]     | ['category-ext-id-1', 'category-ext-id-2']
	}
}
