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
- Atomic file replacement and contained path resolution utilities.
- VibeCore License (PolyForm Noncommercial 1.0.0 with additional terms) and NOTICE, packaged in the JAR.
- Unit tests for configuration, messages, modules, workers, files, project model, variables and identity registry.

### Documentation

- Architecture, roadmap, compatibility and dependency documents describe the implemented foundation.
- Contribution, issue, merge request and local publication review templates.
