# Target Installer Ownership Boundaries

`pde target install` should behave as part of one `pde` product, but Eclipse p2
provisioning still needs an Equinox/OSGi runtime. This inventory defines the
safe boundary for moving target-installer support code into the Kotlin `pde`
side without changing live p2 behavior.

## Move Or Share In Kotlin `pde`

These pieces are regular CLI/config/file-model logic and should be owned by
`pde`:

- Target-install input modeling: profile id, p2 area, target definition,
  install root, bundle pool, installer launcher path, and generated installer
  arguments.
- Config defaults and validation for `target.profileId`, `target.p2Path`,
  `target.install`, `target.bundlePool`, `target.definition`, and
  `target.installer`.
- Relative path resolution from the config directory.
- Target definition discovery from the config directory when
  `target.definition` is omitted.
- Non-p2 `.target` metadata parsing: repository locations, installable-unit
  references, launch program/vm arguments, and `includeConfigurePhase`.
- Packaged launcher discovery and validation for the internal
  `target-installer-launcher.jar` bundled with the CLI distribution.
- Process invocation contract for the standalone launcher: Java executable,
  `java -jar`, cache mode, argument order, working directory, stdout/stderr
  forwarding, and error handling before the p2 runtime starts.
- Regression tests that can run without provisioning a real target platform.

## Keep Inside The Equinox Target Installer

These pieces are runtime-sensitive p2/OSGi logic and should stay behind the
standalone target-installer process boundary:

- `IApplication` startup and `IApplicationContext` access.
- `IProvisioningAgent` and `IProvisioningAgentProvider` lifecycle.
- p2 metadata and artifact repository managers.
- Repository loading and p2 installable-unit queries.
- Profile creation, deletion, upsert, registry access, and bundle-pool
  semantics.
- `ProvisioningSession`, `InstallOperation`, `UpdateOperation`, and
  `ProvisioningContext` setup.
- `ProfileModificationJob` execution and progress monitors.
- p2 planner status, conflict reporting, warning/error handling, and phase
  selection such as skipping configure/unconfigure phases.
- Any dependency on internal or provisional Eclipse p2 APIs.
- OSGi service activation and logging that only exists in the Equinox runtime.

## Defer To #100

These decisions need a separate runtime feasibility spike and should not block
the low-risk unification work:

- Replacing the Java Equinox application with a Kotlin OSGi application.
- Embedding Equinox/p2 services directly inside the main `pde` CLI process.
- Changing p2 service setup, provisioning-agent ownership, or repository-manager
  lifecycle.
- Replacing p2 operation/progress/status handling with a different abstraction.
- Changing release packaging for the p2 runtime beyond making the current
  launcher an internal `pde` distribution artifact.

## Current Class Ownership

- `apps/pde-launch` and `core/pde-launch-engine`: own public CLI UX, YAML schema,
  config loading, path defaults, target discovery, packaged launcher lookup,
  process invocation, and non-p2 tests.
- `core/pde-launch-engine/.../TargetFileParser.kt`: should be the shared parser
  for non-p2 `.target` metadata used by target install, target mirror, launch,
  and tests.
- `tools/target-installer/.../ReadProperties.java`: should remain a thin
  compatibility parser for normalized arguments emitted by Kotlin `pde`.
- `tools/target-installer/.../TargetFileParser.java`: should be reduced or
  removed once the standalone launcher receives all non-p2 target metadata it
  needs from Kotlin `pde`.
- `tools/target-installer/.../Application.java`, `Activator.java`,
  `Repository.java`, `Operation.java`, and p2 helper methods in `Utils.java`:
  remain inside the Equinox application.

## Near-Term Implementation Rule

Move the seam toward this flow while preserving behavior:

```text
pde CLI/config/schema/tests
        |
        v
Kotlin target install model and .target metadata parser
        |
        v
normalized launcher process invocation
        |
        v
minimal Equinox application for live p2 provisioning
```

Every step should keep the last box narrow and runtime-focused, with local tests
covering the upper three boxes without starting p2.
