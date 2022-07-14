package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.*
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismOptionSourceProvider extends AbstractOptionSourceProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext

	NutanixPrismOptionSourceProvider(NutanixPrismPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return 'nutanix-prism-option-source-plugin'
	}

	@Override
	String getName() {
		return 'Nutanix Prism Option Source Plugin'
	}

	@Override
	List<String> getMethodNames() {
		return new ArrayList<String>([])
	}
}
