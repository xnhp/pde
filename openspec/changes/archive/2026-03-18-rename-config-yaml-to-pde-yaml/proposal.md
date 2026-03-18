## Why

The project uses `config.yaml` as the primary configuration filename, which is generic and does not clearly indicate that it belongs to PDE tooling. Renaming it to `pde.yaml` makes configuration files self-describing and avoids ambiguity when multiple tools are used in the same workspace.

## What Changes

- Rename the canonical configuration filename from `config.yaml` to `pde.yaml` across CLI tools and related documentation.
- Update configuration discovery, validation messages, examples, and tests to reference `pde.yaml`.
- **BREAKING**: remove support for `config.yaml`; no fallback lookup or migration behavior will be provided.

## Capabilities

### New Capabilities
- `pde-config-filename`: Define `pde.yaml` as the required and only supported PDE configuration filename.

### Modified Capabilities
- None.

## Impact

- Affected areas: resolver CLI config loading, launch planning config loading, shared config parsing paths, tests, and user-facing docs.
- No external dependency changes are expected.
- Users must rename existing configuration files to `pde.yaml` before using updated tooling.
