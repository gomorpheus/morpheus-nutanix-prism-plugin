# Morpheus Nutanix Prism Central Plugin

This plugin provides a full integration between [Nutanix Prism Central](https://www.nutanix.com/products/prism) and [Morpheus](https://morpheusdata.com). It enables cloud inventory sync, VM provisioning, snapshot-based backups, IaC resource mapping, and Nutanix Flow network security management from within the Morpheus platform.

## Requirements

| Component | Minimum Version |
|-----------|----------------|
| Morpheus | 9.0.0 |
| Nutanix Prism Central | pc.7.3 |
| Nutanix AOS | 7.3 |
| Nutanix AHV | 10.3 |
| Nutanix NCC | 4.6.0 |

> **Note:** As of the V4 REST migration, this plugin standardizes on the Nutanix `vmm` v4.3 API
> (required for VM-level project scoping). Environments below pc.7.3/AOS 7.3/AHV 10.3 are not
> supported.

## Installation

1. Download the latest `.jar` from the [Releases](https://github.com/HewlettPackard/morpheus-nutanix-prism-plugin/releases) page, or [build it yourself](#building).
2. In Morpheus, navigate to **Administration → Integrations → Plugins**.
3. Click **Browse** and upload the `.jar` file.
4. The **Nutanix Prism Central** cloud type will appear after the plugin loads.

## Configuration

When adding a Nutanix Prism Central cloud in Morpheus (**Infrastructure → Clouds → Add Cloud**), provide the following:

| Field | Description |
|-------|-------------|
| **API URL** | Prism Central endpoint, e.g. `https://10.100.10.100:9440/` |
| **Username** | Prism Central admin username |
| **Password** | Prism Central admin password |

Credentials can also be stored as a Morpheus [Credential](https://docs.morpheusdata.com/en/latest/administration/credentials/credentials.html) and selected at cloud setup time.

## Features

### Cloud Sync
The following resources are discovered and kept in sync from Prism Central:

- **Clusters** — physical clusters managed by Prism Central
- **Hosts** — hypervisor nodes within each cluster
- **Virtual Machines** — running and stopped VMs
- **Images** — disk images available for provisioning
- **Templates** — VM templates
- **Networks** — AHV networks and virtual private clouds (VPCs)
- **Datastores** — storage containers
- **Categories** — Nutanix category key/value pairs
- **Projects** — Prism Central projects

Any additions, updates, and removals in Nutanix are automatically reflected in Morpheus on the next sync cycle.

### Provisioning
Virtual machines can be provisioned into Nutanix Prism Central directly from Morpheus using standard instance types and layouts. Supported operations include:

- Create, start, stop, restart, and delete VMs
- Resize CPU, memory, and storage
- Clone and snapshot workflows
- Multi-NIC and multi-disk configurations
- Cloud-init and sysprep guest customization

### Backups
VM snapshots are supported via the Morpheus backup framework. Snapshots are created, listed, and restored through the Morpheus UI without leaving the platform.

### IaC Resource Mapping
The plugin implements `IacResourceMappingProvider`, enabling Morpheus to map Terraform and other IaC-managed resources back to Nutanix VM objects for lifecycle management.

## Building

```bash
./gradlew shadowJar
```

The plugin JAR will be written to `build/libs/`.

## License

Copyright 2024 Morpheus Data, LLC. Licensed under the [Apache License, Version 2.0](LICENSE).
