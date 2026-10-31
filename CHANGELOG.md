# Changelog

## Unreleased

### Added

- Maven build for JDK 25 against the Paper 1.21.4 API.
- Plugin lifecycle with tracked scheduler tasks and a bounded background worker pool.
- Validated `config.yml` and message loading. `/vibecore reload` validates on a worker and keeps the previous configuration when any problem is found.
- English and Turkish messages with safe placeholder insertion; players see their client language when available.
- Module registry with isolated start-up and honest states: enabled, disabled, not implemented, unsupported and failed.
- `/vibecore help`, `status`, `diagnostics` and `reload` with separate permissions and permission-aware completion.
- Shared model foundations: namespaced identifiers, project headers, schema migration chain, typed and scoped variables, and a persistent glyph and model data identity registry.
- Variable store with per-player isolation, scope-aware lifecycle clearing, bounded per-player values and lossless persistence of persistent scopes.
- Condition and action engine: typed comparisons and boolean logic, variable actions, branches and fail gates, all-or-nothing commit of variable changes, step and nesting budgets, and a bounded YAML parser.
- Menu items accept an item model key, custom model data and player heads (the viewer, a UUID or a texture value restricted to the official skin host), and state overrides can change them.
- Built-in player data in conditions (`{data: name}`) and text (`<data:name>`), and optional PlaceholderAPI text (`<papi:identifier>`), looked up at run time without a build dependency.
- Command actions: `emit: {type: command, as: console|player, run: ...}` with an owner allow list in `actions.allowed-commands`, a fixed refusal of operator, stop and reload commands, typed `<player>`, `<uuid>` and number or boolean `<var:key>` placeholders, single-line templates without a leading slash, and an audit log line for console commands.
- `cooldown` action: blocks a chain until a period has passed since the time stored in a number variable, with a message showing the remaining time; the variable's scope makes it per session, per player or global. Number variables accept bounds up to 1e15 and decimal bounds.
- Shop actions: `money-take` and `money-give` through an optional Vault economy, `item-take` and `item-give` on plain items only, and the `has-money`, `has-item` and `has-permission` conditions. Takes run before gives on one snapshot; failures revert earlier steps, and an unrevertable step is logged as compensation-required in `data/transactions.log`.
- `Facts` and `SideEffects` let the logic engine consult and change the outside world together with variables, all or none.
- Inventory menus at runtime: protected sessions that cancel every inventory interaction, debounced clicks with an in-flight lock, permission re-check on click, state and visibility rendering with `<var:key>` and `<player>` text, message, sound, open, close and refresh effects, exactly-once close actions, and `/vibecore menu open`, `close` and `list` with separate permissions.
- Menu and variable files under `menus/` and `variables.yml` validated together on start-up and reload, including references to unknown variables and menus; persistent variables saved atomically to `data/variables.txt`.
- Bundled example menu and variables.
- Inventory menu definitions: title, rows, open permission and condition, open and close actions, items with slots, look, ordered state overrides, visibility and per-click actions, validated as a whole with path-qualified errors.
- Atomic file replacement and contained path resolution utilities.
- VibeCore License (PolyForm Noncommercial 1.0.0 with additional terms) and NOTICE, packaged in the JAR.
- Unit tests for configuration, messages, modules, workers, files, project model, variables and identity registry.

### Documentation

- Architecture, roadmap, compatibility and dependency documents describe the implemented foundation.
- Contribution, issue, merge request and local publication review templates.
