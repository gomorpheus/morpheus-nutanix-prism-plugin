package com.morpheusdata.nutanix.prism.plugin.utils

import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismSyncUtils {

	static buildSyncLists(existingItems, masterItems, matchExistingToMasterFunc) {
		log.debug "buildSyncLists: ${existingItems}, ${masterItems}"
		def rtn = [addList:[], updateList: [], removeList: []]
		try {
			existingItems?.each { existing ->
				def matches = masterItems?.findAll { matchExistingToMasterFunc(existing, it) }
				if(matches?.size() > 0) {
					matches?.each { match ->
						rtn.updateList << [existingItem:existing, masterItem:match]
					}
				} else {
					rtn.removeList << existing
				}
			}
			masterItems?.each { masterItem ->
				def match = rtn?.updateList?.find {
					it.masterItem == masterItem
				}
				if(!match) {
					rtn.addList << masterItem
				}
			}
		} catch(e) {
			log.error "buildSyncLists error: ${e}", e
		}
		return rtn
	}
}
