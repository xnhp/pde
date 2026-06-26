# JaCoCo Coverage

`pde run` and `pde test` already pass configured `vmArgs` to the forked Eclipse JVM.
That is enough to collect JaCoCo execution data without a dedicated `pde --coverage`
flag.

## Collect Execution Data

Download or otherwise provide a JaCoCo agent jar that supports the Java version used
by the launch. Use an absolute `destfile` path so the forked Eclipse JVM writes the
`.exec` file somewhere stable, independent of its `-data` workspace.

```yaml
tests:
  - name: GatewayDefaultServiceTests
    testPluginName: org.knime.gateway.impl
    className: org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests
    vmArgs:
      - -javaagent:/abs/tools/jacoco/org.jacoco.agent-runtime.jar=destfile=/abs/work/coverage/gateway.exec,append=true
```

The same `vmArgs` entry can be added to a `launches:` preset for `pde run`.

Run the usual commands:

```bash
pde compile
pde test GatewayDefaultServiceTests
```

## Generate Reports

Use JaCoCo CLI against the collected `.exec` file and the compiled workspace bundle
outputs. Repeat `--classfiles` and `--sourcefiles` for every bundle that should be
included in the report.

```bash
java -jar /abs/tools/jacoco/org.jacoco.cli-nodeps.jar report \
  /abs/work/coverage/gateway.exec \
  --classfiles /abs/repos/knime/org.knime.gateway.impl/bin \
  --sourcefiles /abs/repos/knime/org.knime.gateway.impl/src \
  --xml /abs/work/coverage/jacoco.xml \
  --html /abs/work/coverage/html
```

If a bundle uses custom `bundles[].classRoots`, pass those output directories as the
`--classfiles` inputs instead of `bin`.

## Current Scope

The original PR #110 coverage code added convenience helpers for bundled JaCoCo jar
discovery, per-run `.exec` naming, and report command assembly. Those are useful
ergonomics, but they are not required for collecting or reporting coverage: the
existing launch/test configuration model already exposes the JVM hook JaCoCo needs.
Keep coverage as a documented launch-config workflow unless repeated use shows that
first-class jar discovery or report assembly removes enough manual work to justify a
new command.
