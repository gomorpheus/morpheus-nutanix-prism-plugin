package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.cloud.MorpheusComputeZonePoolService
import spock.lang.Specification
import spock.lang.Subject

class NutanixPrismCloudProviderSpec extends Specification {

	@Subject
	NutanixPrismCloudProvider service

	MorpheusContext context
	MorpheusCloudService cloudContext
	MorpheusComputeZonePoolService poolContext
	NutanixPrismPlugin plugin

	void setup() {
		context = Mock(MorpheusContext)
		cloudContext = Mock(MorpheusCloudService)
		poolContext = Mock(MorpheusComputeZonePoolService)
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
		optionTypes.size() == 0
	}

	void "getComputeServerTypes"() {
		when:
		def serverTypes = service.getComputeServerTypes()

		then:
		serverTypes.size() == 0
	}
}
