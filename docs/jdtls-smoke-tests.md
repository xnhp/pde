# JDT LS smoke tests (real workspaces)

The JDT LS smoke tests include optional real-workspace checks (e.g. knime-gateway,
knime-server-client). These are slow and require local repo checkouts, so they are
skipped by default unless you opt in.

## Enable real-workspace smoke tests

```bash
JDTLS_REAL_WORKSPACE=1 ./gradlew :pde-launch:test --tests cn.varsa.pde.launch.JdtlsSmokeTest
./gradlew :pde-launch:test --tests cn.varsa.pde.launch.JdtlsSmokeTest -Djdtls.real.workspace=true
```

## Issue-workspace import smoke test

The issue-workspace import smoke test is split into its own class and has its own
opt-in toggle (`JDTLS_IMPORT_REAL`). It validates that `jdtls-init` produces a usable
`projectConfigurations.json` and that JDT LS can import it. It expects an issue
directory layout with a `config.yaml` at the root and a `.jdtls-data` directory.

```bash
JDTLS_IMPORT_REAL=1 \
  JDTLS_ISSUE_ROOT=~/issues/td-123456 \
  ./gradlew :pde-launch:test --tests cn.varsa.pde.launch.JdtlsImportSmokeTest
```

### Optional overrides

- `JDTLS_ISSUE_CONFIG` to point at a non-standard config path.
- `JDTLS_IMPORT_EXPECT` (comma-separated project names) to assert imported projects via
  `java.project.list` (falls back to `java.project.getAll` when unsupported).
  If unset, the smoke test logs that the project list assertion is skipped.
- `JDTLS_IMPORT_NAV_FILE` to a source file for a cross-bundle definition check
  (absolute or relative to `JDTLS_ISSUE_ROOT`).
- `JDTLS_IMPORT_NAV_SYMBOL` for the symbol at the definition request location.
- `JDTLS_IMPORT_NAV_EXPECT` (comma-separated path fragments) to validate the definition
  result. If any `JDTLS_IMPORT_NAV_*` values are missing, the navigation check is skipped.
- `JDTLS_IMPORT_NAV2_FILE` to a second source file for another cross-bundle definition check
  (absolute or relative to `JDTLS_ISSUE_ROOT`).
- `JDTLS_IMPORT_NAV2_SYMBOL` for the symbol at the second definition request location.
- `JDTLS_IMPORT_NAV2_EXPECT` (comma-separated path fragments) to validate the second definition
  result. If any `JDTLS_IMPORT_NAV2_*` values are missing, the second check is skipped.

Example with navigation check enabled:

```bash
JDTLS_IMPORT_REAL=1 \
  JDTLS_ISSUE_ROOT=~/issues/td-03c3e2 \
  JDTLS_IMPORT_NAV_FILE=knime-gateway/org.knime.gateway.impl/src/org/knime/gateway/impl/space/LocalSpaceProvider.java \
  JDTLS_IMPORT_NAV_SYMBOL=SpaceProvider \
  JDTLS_IMPORT_NAV_EXPECT=org.knime.gateway.api/src/ \
  ./gradlew :pde-launch:test --tests cn.varsa.pde.launch.JdtlsImportSmokeTest
```
