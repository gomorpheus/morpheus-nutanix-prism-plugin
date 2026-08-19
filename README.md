# Morpheus Nutanix Prism Central Plugin

The Morpheus Nutanix Prism Central Plugin integrates Morpheus with [Nutanix Prism Central](https://www.nutanix.com/products/prism) to provide cloud inventory sync, virtual machine provisioning, snapshot-based backups, and IaC resource mapping. The plugin communicates with the Prism Central v3 and v2.0 REST APIs and the Nutanix VMM API.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Repository structure](#repository-structure)
- [Building the plugin](#building-the-plugin)
- [License](#license)
- [Installing](#installing)
- [Detailed Usage Steps](#detailed-usage-steps)
- [API Endpoints](#api-endpoints)

---

## Features

### Cloud Sync

Morpheus synchronises the following Prism Central resources for inventory:

- Clusters
- Hosts
- Virtual machines
- Images
- Templates
- Networks (AHV subnets)
- Virtual private clouds (VPCs)
- Datastores (storage containers)
- Categories
- Projects
- Snapshots

Additions, updates, and removals in Nutanix are reflected in Morpheus on the next sync cycle.

### Provisioning

Provision virtual machines into Prism Central from Morpheus using standard instance types and layouts. Supported operations include:

- Create, start, stop, restart, and delete virtual machines
- Resize CPU, memory, and storage
- Clone and snapshot workflows
- Multi-NIC and multi-disk configurations
- Cloud-init and sysprep guest customization

### Backups

Virtual machine snapshots are exposed through the Morpheus backup framework. Snapshots can be created, listed, restored, and removed from the Morpheus UI.

### IaC Resource Mapping

Maps Terraform and other IaC-managed resources back to Nutanix virtual machine objects so Morpheus can manage their lifecycle.

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Morpheus | 9.0.0 or later |
| Java | 11 or later |
| Gradle | Use the included Gradle wrapper (`./gradlew`) |
| Nutanix Prism Central | pc.2022.6 or later |
| Nutanix NCC | 4.6.0 or later |

Additional prerequisites:

- A reachable Prism Central endpoint accessible over HTTPS from the Morpheus appliance (default port 9440)
- A Prism Central user account with administrative API access
- Network access from the Morpheus appliance to Prism Central on the configured port

---

## Repository structure

```
src/main/groovy/com/morpheusdata/nutanix/prism/plugin/
├── NutanixPrismPlugin.groovy                     - Plugin entry point; registers all providers
├── NutanixPrismCloudProvider.groovy              - Cloud provider; cloud OptionTypes and sync orchestration
├── NutanixPrismProvisionProvider.groovy          - Virtual machine provisioning and lifecycle operations
├── NutanixPrismOptionSourceProvider.groovy       - UI option source data
├── NutanixPrismIacResourceMappingProvider.groovy - Maps IaC resources to Nutanix virtual machines
├── backup/                                       - Backup provider and snapshot provider
├── sync/                                         - Per-resource sync classes (clusters, hosts, VMs, images,
│                                                   templates, networks, VPCs, datastores, categories,
│                                                   projects, snapshots)
└── utils/                                        - Nutanix API client and compute utilities
src/main/groovy/com/morpheusdata/retry/           - Retry policy support for API calls
src/main/resources/i18n/                          - Localization bundles
src/main/resources/scribe/                        - Compute type layout definitions
build.gradle, gradle.properties                   - Build configuration and plugin metadata
```

---

## Building the plugin

Run the following command to compile and package the plugin jar:

```bash
./gradlew shadowJar
```

The packaged jar will be written to `build/libs/`.

To execute tests, use the following command:

```bash
./gradlew test
```

---

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for details.

---

## Installing

1. Build the plugin (see [Building the plugin](#building-the-plugin)) or download a released jar from the [Releases](https://github.com/HewlettPackard/morpheus-nutanix-prism-plugin/releases) page.
2. In Morpheus, navigate to **Administration > Integrations > Plugins**.
3. Click **Add** and upload the `morpheus-nutanix-prism-plugin-<version>.jar` from `build/libs/`.
4. Navigate to **Infrastructure > Clouds > Add Cloud** and select **Nutanix Prism Central** to configure the integration.

---

## Detailed Usage Steps

### Adding a Nutanix Prism Central Cloud

1. Go to **Infrastructure > Clouds > Add Cloud**.
2. Select **Nutanix Prism Central** as the cloud type.
3. Enter the **API URL** (for example `https://10.100.10.100:9440/`), **Username**, and **Password**, or select a stored [Credential](https://docs.morpheusdata.com/en/latest/administration/credentials/credentials.html).
4. Select the target **Group** and configure inventory options as required.
5. Save. Morpheus connects to Prism Central and begins syncing clusters, hosts, virtual machines, images, networks, and related resources.

### Provisioning a Virtual Machine

Once the cloud has synced, provision through **Provisioning > Instances > Add Instance** and select the Nutanix Prism Central cloud. Choose an instance type, layout, and plan. Morpheus creates the virtual machine through the Prism Central API and applies guest customization.

### Backing Up a Virtual Machine

Snapshots are managed through the Morpheus backup framework. Create a backup against a Nutanix instance from the instance **Backups** tab, and restore from the same location.

---

## API Endpoints

This plugin communicates with Prism Central at the configured API URL. Authentication uses HTTP Basic credentials. The plugin uses three API surfaces: the v3 API (`api/nutanix/v3`), the v2.0 API (`api/nutanix/v2.0`), and the VMM API (`api/vmm/{version}`).

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `api/nutanix/v3/clusters/list` | POST | List clusters |
| `api/nutanix/v3/groups` | POST | Query hosts, storage containers, and metrics by entity type |
| `api/nutanix/v3/vms/list` | POST | List virtual machines |
| `api/nutanix/v3/vms` | POST | Create a virtual machine |
| `api/nutanix/v3/vms/{uuid}` | GET | Retrieve a virtual machine |
| `api/nutanix/v3/vms/{uuid}` | PUT | Update or resize a virtual machine |
| `api/nutanix/v3/vms/{uuid}` | DELETE | Delete a virtual machine |
| `api/nutanix/v3/vms/{uuid}/clone` | POST | Clone a virtual machine |
| `api/nutanix/v3/images/list` | POST | List images |
| `api/nutanix/v3/images` | POST | Create an image |
| `api/nutanix/v3/images/{uuid}` | GET | Retrieve an image |
| `api/nutanix/v3/images/{uuid}/file` | PUT | Upload image file content |
| `api/nutanix/v3/subnets/list` | POST | List networks |
| `api/nutanix/v3/vpcs/list` | POST | List virtual private clouds |
| `api/nutanix/v3/categories/list` | POST | List categories |
| `api/nutanix/v3/categories/{key}` | GET | Retrieve a category key |
| `api/nutanix/v3/categories/{key}/{value}` | GET | Retrieve a category value |
| `api/nutanix/v3/projects/list` | POST | List projects |
| `api/nutanix/v3/projects/{uuid}` | GET | Retrieve a project |
| `api/nutanix/v3/tasks/{uuid}` | GET | Poll an asynchronous task |
| `api/nutanix/v2.0/snapshots` | GET | List snapshots |
| `api/nutanix/v2.0/snapshots` | POST | Create a snapshot |
| `api/nutanix/v2.0/snapshots/{uuid}` | GET | Retrieve a snapshot |
| `api/nutanix/v2.0/snapshots/{uuid}` | DELETE | Delete a snapshot |
| `api/nutanix/v2.0/snapshots/{uuid}/clone` | POST | Clone from a snapshot |
| `api/nutanix/v2.0/vms/{uuid}/restore` | POST | Restore a virtual machine from a snapshot |
| `api/vmm/{version}/templates` | GET | List templates |
| `api/vmm/{version}/templates/{uuid}` | GET | Retrieve a template |
