## 1. Configuration Filename Resolution

- [x] 1.1 Locate all default config filename resolution paths in shared/core modules and app entry points.
- [x] 1.2 Replace default filename constants and lookup logic from `config.yaml` to `pde.yaml`.
- [x] 1.3 Ensure discovery failure paths and diagnostics explicitly require `pde.yaml` with no legacy fallback.

## 2. Tooling and User-Facing Surface

- [x] 2.1 Update CLI help text, usage output, and examples to reference `pde.yaml` only.
- [x] 2.2 Update repository documentation that references the default config filename.

## 3. Verification

- [x] 3.1 Update or add tests covering successful discovery of `pde.yaml` and failure when only `config.yaml` is present.
- [x] 3.2 Run relevant Gradle checks for affected modules and confirm no user-facing `config.yaml` references remain.
