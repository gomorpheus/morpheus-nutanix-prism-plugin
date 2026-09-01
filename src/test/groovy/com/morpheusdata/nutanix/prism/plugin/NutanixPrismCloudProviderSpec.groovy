package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.cloud.MorpheusCloudPoolService
import spock.lang.Specification
import spock.lang.Subject

class NutanixPrismCloudProviderSpec extends Specification {

	@Subject
	NutanixPrismCloudProvider service

	MorpheusContext context
	MorpheusCloudService cloudContext
	MorpheusCloudPoolService poolContext
	NutanixPrismPlugin plugin

	void setup() {
		context = Mock(MorpheusContext)
		cloudContext = Mock(MorpheusCloudService)
		poolContext = Mock(MorpheusCloudPoolService)
		context.async.getCloud() >> cloudContext
		cloudContext.getPool() >> poolContext
		plugin = Mock(NutanixPrismPlugin)

		service = new NutanixPrismCloudProvider(plugin, context)
	}

	void "DI works"() {
		expect:
		service.morpheus
	}

	void "getOptionTypes"() {
		when:
		def optionTypes = service.getOptionTypes()

		then:
		optionTypes.size() == 9
		optionTypes*.code as Set == ['nutanix-prism-api-url', 'nutanix-prism-credential', 'nutanix-prism-username',
									  'nutanix-prism-password', 'nutanix-prism-project', 'nutanix-prism-vmm-api-version',
									  'nutanix-prism-import-existing', 'nutanix-prism-enableVnc',
									  'nutanix-prism-windows-nic-config-mode'] as Set
	}

	void "getComputeServerTypes"() {
		when:
		def serverTypes = service.getComputeServerTypes()

		then:
		serverTypes.size() == 8
		serverTypes*.code as Set == ['nutanix-prism-hypervisor', 'nutanix-prism-server', 'nutanix-prism-vm',
									 'nutanix-prism-windows-vm', 'nutanix-prism-unmanaged', 'nutanix-prism-linux',
									 'nutanix-prism-kube-master', 'nutanix-prism-kube-worker'] as Set
	}
}
