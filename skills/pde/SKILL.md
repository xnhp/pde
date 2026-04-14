---
name: pde CLI
description: "Compile and run OSGi/Eclipse-PDE projects and tests"
---

Note: This replaces in effect the maven configuration in many of our OSGi/Eclipse-PDE projects.
Do _not_ try to use any maven config or run any maven commands in these projects when you can use `pde` instead.
This maven config is only for CI and does not run on a local dev machine.

1. Set up `pde.yaml` if not present. See the schema file at the path returned
by `pde schema`. Use the includes at `~/Desktop/issues/`. Example `pde.yaml`
```yaml
bundles:
    - path: knime-com-gateway_todo_NXT-4645-safe-concurrency-on-gateway-server-s/com.knime.gateway.executor
    - path: knime-com-gateway_todo_NXT-4645-safe-concurrency-on-gateway-server-s/com.knime.gateway.executor.tests
includes:
    - ../launches.yaml
    - ../target.yaml
tests:
    - className: com.knime.gateway.executor.websocket.MultiKeyBidiMapTest
      debug: false
      name: MultiKeyBidiMapTest
      programArgs: []
      runner: junit5
      testPluginName: com.knime.gateway.executor  # note: not the test fragment bundle
      vmArgs: []
```

2. Run `pde target install` to install dependencies. If this fails, other PDE commands will not work.

3. Build with `pde compile`. Some warnings can be ignored.

4. Run product with `pde launch` or run tests with `pde test`.

If compilation fails due to missing libraries, look for `lib/fetch_jars` in bundles and inspect the readme there on how to pull extra dependencies.

For all commands, refer to their subcommand `--help`.

# Configuring tests

Note that for `tests` launches, the `testPluginName` is the main bundle and _not_ the `.tests` fragment bundle.