package com.morpheusdata.nutanix.prism.plugin.backup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.MorpheusBackupProvider

class NutanixPrismBackupProvider extends MorpheusBackupProvider {

	NutanixPrismBackupProvider(Plugin plugin, MorpheusContext morpheusContext) {
		super(plugin, morpheusContext)

		NutanixPrismSnapshotProvider nutanixPrismSnapshotProvider = new NutanixPrismSnapshotProvider(plugin, morpheusContext)
		plugin.pluginProviders.put(nutanixPrismSnapshotProvider.code, nutanixPrismSnapshotProvider)
		addScopedProvider(nutanixPrismSnapshotProvider, "nutanix-prism-provision-provider", null)
	}

}
