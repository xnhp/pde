
## Set up configuration `pde.yaml`

Configuration is documented exhaustively in a json-schema at the path returned by `pde schema`.

[Here](config-yaml.md) are some non-exhaustive examples.


## Install target platform state: `pde target install`

If not otherwise specified, this will set up a p2 profile (usable as target platform state) in the current working
directory, based on a _shared_ bundle pool. This means

1. You have to download dependencies only once
2. The individual profiles are still completely isolated

## Compile your sources: `pde compile`

<details>
<summary>
We have basic module-level change detection
</summary>

```
➜ 15:39 ben todo_NXT-4622-executor-to-serve-web-resources-dire pde compile
[INFO] Discovered launch config in /home/ben/Desktop/issues/todo_NXT-4622-executor-to-serve-web-resources-dire/pde.yaml and will use it.
[WARN] Using lowercase profile registry path: /home/ben/Desktop/issues/todo_NXT-4622-executor-to-serve-web-resources-dire/target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.profile
[INFO] skipped com.knime.gateway.executor: Up-to-date; compile skipped
[INFO] skipped com.knime.gateway.executor.tests: Up-to-date; compile skipped
```

</details>

## Set up and run a PDE Application/Product

```
launches:
  - name: Analytics-Platform
    product: org.knime.product.KNIME_PRODUCT
    application: org.knime.product.KNIME_APPLICATION
    splash: org.knime.product
    vmArgs:
      - -ea
      [...]
    env:
      KNIME_PROFILE: development
      FEATURE_FLAG: "true"
```


## Set up and run unit tests

Configure a test in `pde.yaml` (see schema definition for guaranteed up-to-date info)

```
tests:
    - className: com.knime.gateway.executor.server.GatewayServerEarlyStartupTest
      debug: false
      name: foo
      programArgs: []
      runner: junit5
      testPluginName: com.knime.gateway.executor
      vmArgs: []
```

Then launch with `pde test` (runs all configured tests) or `pde test foo` to run a specific test
and observe nice output:

```
➜ 15:36 ben todo_NXT-4622-executor-to-serve-web-resources-dire pde test
[TEST] START testNeitherSet()
[PASS] testNeitherSet() (36ms)
[TEST] START testSystemPropertySet()
[PASS] testSystemPropertySet() (0ms)
[TEST] START testEnvVarSet()
[PASS] testEnvVarSet() (0ms)
[TEST] START testEnvVarTakesPrecedenceOverSystemProperty()
[PASS] testEnvVarTakesPrecedenceOverSystemProperty() (0ms)
[INFO] Remote test session finished at 2026-03-31T13:36:47.243668156Z
[INFO] Tests=4 failed=0 errors=0 elapsedMs=122
```

(Dont forget to compile first e.g. with `pde compile`)
