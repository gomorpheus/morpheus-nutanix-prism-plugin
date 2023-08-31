package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.cloud.MorpheusCloudPoolService
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

class NutanixPrismOptionSourceProviderSpec extends Specification {

	@Subject
	NutanixPrismOptionSourceProvider service

	MorpheusContext context
	MorpheusCloudService cloudContext
	MorpheusCloudPoolService poolContext
	NutanixPrismPlugin plugin
	@Shared NutanixPrismCloudProvider nutanixPrismCloudProvider

	void setup() {
		context = Mock(MorpheusContext)
		cloudContext = Mock(MorpheusCloudService)
		poolContext = Mock(MorpheusCloudPoolService)
		context.async.getCloud() >> cloudContext
		cloudContext.getPool() >> poolContext
		plugin = Mock(NutanixPrismPlugin)
		nutanixPrismCloudProvider = new NutanixPrismCloudProvider(plugin, context)
		service = new NutanixPrismOptionSourceProvider(plugin, context)
	}
}
