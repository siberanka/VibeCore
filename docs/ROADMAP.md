# Roadmap

The core foundation is implemented and unit tested; it has not yet been run on a live server. No feature module is implemented. Features become available only after implementation and acceptance checks.

| Stage | Scope | Completion criteria |
| --- | --- | --- |
| Foundation (in progress) | Lifecycle, configuration, commands, permission checks, session ownership | Local build passes; invalid reload keeps active state; sessions close cleanly |
| Menu engine | Configurable inventory menus, pagination, requirements, actions, conditions and cooldowns | Transaction-safe interaction tests and player acceptance scenarios |
| Cursor interfaces | Display-based cursor surfaces, hover and click handling, layouts and resource pack assets | Correct hit testing, bounded updates, per-player cleanup and client checks |
| Bedrock access | Optional forms and capability-aware navigation | Java and Bedrock acceptance runs; integration absence handled clearly |
| Holograms | Text, items, blocks, images and animated media; visibility and interaction | Persistent definitions, safe decoding limits, cleanup and performance measurements |
| Rank badges | Visual badge generation, styling, font mappings and resource pack export | Deterministic valid packs; documented client version constraints |
| Editing | Authenticated editing, previews, validation and controlled persistence | Threat review, authorization checks, bounded requests and recoverable writes |
| Integrations | Optional placeholders, permissions and pack providers | Versioned adapter tests; isolated failures and no compulsory integrations |

Each stage requires documentation, permission review and checks on the supported Paper and client versions. Detailed implementation issues should be created from the repository templates after publication.
