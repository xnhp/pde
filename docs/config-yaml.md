# pde YAML config

The `pde` CLI (`run`, `compile`, `test`, `api-analyze`, `target`) reads a strict YAML config.
The schema is the single source of truth for property-level semantics, defaults, and descriptions.

## Schema-first reference

- Show the active schema path for your build/runtime: `pde schema`
- Main schema file in this repo: `core/pde-launch-engine/src/main/resources/schema/pde.schema.yaml`
- Candidate/editing copy: `pde-candidate.schema.yaml`

When docs and schema differ, treat the schema as authoritative.

## File discovery

If `--config` is omitted, the CLI discovers a nearby config file using these names:

- `pde.yaml`
- `launch.yaml`
- `launch.yml`
- `pde-launch.yaml`
- `pde-launch.yml`

## Minimal examples

### Basic target + workspace

```yaml
target:
  definition: /abs/path/to/knime.target
  eclipseRuntimeCache: .cache/eclipse-runtime
  p2Repositories:
    - https://download.eclipse.org/releases/2024-12

bundles:
  - path: /abs/path/to/knime/org.knime.gateway.impl
```

### Includes + launch presets

```yaml
includes:
  - common/target.yaml

launches:
  - name: AP
    product: org.knime.product.KNIME_PRODUCT
    application: org.knime.product.KNIME_APPLICATION
    dataDir: .runtime/data
    configDir: .runtime/config
    workDir: .runtime/work
```

### Test preset

```yaml
tests:
  - name: GatewayDefaultServiceTests
    testPluginName: org.knime.gateway.impl
    className: org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests
```

## Practical notes

- `pde run` and `pde test` require compiled `.class` files in configured class roots.
- `target.eclipseRuntimeCache` can pin where ad-hoc Eclipse runtime archives are cached.
- `target.p2Repositories` can provide p2 source repositories used by runtime bootstrap provisioning.
- If `bundles[].classRoots` is omitted, `pde run/test` first infers roots from
  bundle `build.properties` `output..`, then falls back to `bin`.
- `pde compile` is the explicit ECJ-based compile step.
- `pde jdtls-init` generates metadata only; it does not compile sources.
- `pde api-analyze` reads workspace bundles from config and supports `--baseline-root` overrides.

## Related docs

- CLI command usage: `docs/cli-reference.md`
- JDT LS/Eglot workflow: `docs/jdtls-eglot.md`
- JDT LS smoke tests: `docs/jdtls-smoke-tests.md`
