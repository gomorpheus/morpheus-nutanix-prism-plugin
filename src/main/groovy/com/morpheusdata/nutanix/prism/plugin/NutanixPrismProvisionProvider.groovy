package com.morpheusdata.nutanix.prism.plugin

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse

class NutanixPrismProvisionProvider extends AbstractProvisionProvider {

	NutanixPrismPlugin plugin
	MorpheusContext morpheusContext

	NutanixPrismProvisionProvider(NutanixPrismPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		return []
	}

	@Override
	Collection<OptionType> getNodeOptionTypes() {
		return []
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		return []
	}

	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		ComputeServerInterfaceType computeServerInterface = new ComputeServerInterfaceType([
				code:'nutanix-prism-plugin-normal-nic',
				externalId:'NORMAL_NIC',
				name:'Nutanix Prism Plugin Normal NIC',
				defaultType: true,
				enabled: true,
				displayOrder:1
		])
		[computeServerInterface]
	}

	@Override
	Boolean hasDatastores() {
		return []
	}

	@Override
	Boolean hasNetworks() {
		return []
	}

	@Override
	Boolean hasPlanTagMatch() {
		return []
	}

	@Override
	Integer getMaxNetworks() {
		return null
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse validateInstance(Instance instance, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse validateDockerHost(ComputeServer server, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse<WorkloadResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse stopWorkload(Workload workload) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse startWorkload(Workload workload) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse restartWorkload(Workload workload) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse<WorkloadResponse> getServerDetails(ComputeServer server) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.error()
	}

	@Override
	HostType getHostType() {
		return null
	}

	@Override
	Collection<VirtualImage> getVirtualImages() {
		return []
	}

	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {
		return []
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return plugin
	}

	@Override
	String getCode() {
		return 'nutanix-prism-provision-provider-plugin'
	}

	@Override
	String getName() {
		return 'Nutanix Prism Central'
	}
}
