# Dependency and asset policy

## Current dependencies

| Dependency | Version | Scope | Distributed in JAR | License |
| --- | --- | --- | --- | --- |
| Paper API (`io.papermc.paper:paper-api`), including Adventure and MiniMessage | `1.21.4-R0.1-SNAPSHOT` | Provided by the server | No | Own licenses |
| VaultAPI (`com.github.MilkBowl:VaultAPI`), optional economy bridge | `1.7.1` | Provided by the Vault plugin when installed | No | LGPL-3.0 |
| JUnit Jupiter | `5.11.4` | Tests only | No | EPL-2.0 |

The build uses Apache Maven plugins (Apache License 2.0); they are not part of the plugin JAR. The JAR bundles no third-party code or assets. See [NOTICE](../NOTICE).

## Adding a dependency or asset

Placeholder, economy, protocol, Bedrock, renderer, decoder or editor dependencies each require a separate compatibility and license decision. Before accepting a dependency or asset, record its exact version, origin, author, license, notices, intended use and redistribution obligations. Review transitive dependencies and generated assets too. Preserve required copyright and license texts in distributed artifacts. Server-provided APIs, build-only tools and bundled libraries must be distinguished.

The VibeCore License covers siberanka's work only. It does not relicense other people's software, fonts, icons, images or content derived from their assets. Do not import material without the rights required for its intended use.
