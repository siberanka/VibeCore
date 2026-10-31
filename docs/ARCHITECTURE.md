# Architecture

VibeCore is a single Paper plugin built as one Java module. Feature areas are separated by package and by an explicit module lifecycle rather than by separate artifacts. Validation and data-model code is kept free of Bukkit types where possible so it can be tested without a server.

## Implemented foundation

| Package | Responsibility |
| --- | --- |
| `com.siberanka.vibecore` | Plugin entry point; owns core services and their lifecycle. |
| `core.validation` | `Problem` and `Result` types, and a problem-collecting reader for map documents. Every defect of a candidate is reported in one pass. |
| `core.config` | Configuration schema, candidate loading and `ActiveState`, which replaces the active state only with an accepted candidate and tracks a revision. |
| `core.text` | Locale catalog (English and Turkish) and MiniMessage rendering. Arguments are inserted as literal text, never as formatting or click tags. |
| `core.module` | Module registry. Each module is started in isolation and reports `enabled`, `disabled`, `not-implemented`, `unsupported` or `failed`. |
| `core.task` | Tracked scheduler tasks, cancelled together on disable, and a fixed-size worker pool with a bounded queue that refuses excess work. |
| `core.io` | Atomic file replacement and contained path resolution that rejects traversal, absolute paths and symbolic links. |
| `core.diagnostic` | Redacted runtime snapshot: versions, revisions, module counts, worker and task usage. |
| `command` | `/vibecore` command with per-subcommand permissions and permission-aware help and completion. |
| `project` | Namespaced identifiers, project types, project headers and a gap-free schema migration chain that rejects newer schemas. |
| `variable` | Typed variables (number, boolean, text) with session, online-player, persistent-player and persistent-global scopes, bounds and strict parsing. `VariableStore` keeps per-subject values, clears session and online values on close and disconnect, enforces a per-player limit and persists the persistent scopes in a deterministic format that preserves entries of removed definitions. |
| `menu` | Inventory menu model and parser. States override only the fields they set; slots, materials, amounts, text and channels are validated, and slot conflicts are rejected. |
| `logic` | Typed conditions (compare, all, any, not, chance) and action chains (set, add, toggle, reset, if, fail, emit) parsed from YAML trees with path-qualified errors. `LogicEngine` runs chains fail-fast against staged variable writes and commits them all or none, within step and nesting budgets; effects are returned for the caller to perform after the commit. |
| `pack.registry` | Persistent numeric identity allocation for glyphs and model data with tombstones, reservations and a deterministic text format. |

## Rules for new modules

- Implement `VibeModule`; report missing capabilities through `unsupportedReason()` instead of failing later.
- Release every task, session, entity and render state in `stop()`.
- Keep world and player operations on the server thread. Run file, media and pack work on the bounded workers and return results through the scheduler.
- Validate a complete candidate before replacing active state. An invalid candidate must leave the previous state in effect.
- Hidden or disabled interface elements are not a security control. Check permissions and state again at the moment an action runs.
- Optional integrations belong behind adapters; their absence must not affect unrelated modules.

## Planned components

A shared versioned project model will describe inventory menus, cursor scenes, HUDs, holograms and badges. Typed actions and conditions will be shared by modules. The editor, project store, asset pipeline and pack compiler will enforce authentication, per-project authority, revision conflicts, safe paths and bounded processing. Separate Java and Bedrock render and pack backends will report their supported capabilities.
