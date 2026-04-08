# pde-launch-engine (internal module)

Internal command implementation and helpers for target-platform and compile flows
used by the public `pde` CLI.

## Build and run

- This module is not distributed as a standalone CLI.
- Build and run through the public CLI:
  - `./gradlew :pde-cli:installDist`
  - `./apps/pde-launch/build/install/pde/bin/pde --help`

## Notes

- This module contains command implementation used by `pde run`, `pde target`,
  `pde test`, and `pde api-analyze`.
- User-facing configuration and behavior are documented at:
  - `docs/config-yaml.md`
  - `docs/cli-reference.md`
