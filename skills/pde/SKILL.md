---
name: pde CLI
description: "Compile and run OSGi/Eclipse-PDE projects and tests"
---

Note: This replaces in effect the maven configuration in many of our OSGi/Eclipse-PDE projects.
Do _not_ try to use any maven config or run any maven commands in these projects when you can use `pde` instead.
This maven config is only for CI and does not run on a local dev machine.

1. Set up `pde.yaml` if not present. See the schema file at the path returned
by `pde schema`. Place it either in the issue root or the repo root. Use includes
from `~/Desktop/issues/`.

Issue-root example `pde.yaml`:
```yaml
bundles:
    - path: knime-ui_todo_NXT-4566-path-traversal/org.knime.ui.java
    - path: knime-ui_todo_NXT-4566-path-traversal/org.knime.ui.java.tests
includes:
    - ../../launches.yaml
    - ../../target.yaml
tests:
    - className: org.knime.ui.java.util.UserDirectoryTest
      debug: false
      name: UserDirectoryTest
      programArgs: []
      runner: junit5
      testPluginName: org.knime.ui.java  # note: not the test fragment bundle
      vmArgs: []
```

2. Run PDE commands as separate steps for clearer diagnostics:

   - `pde target install`
   - `pde compile`
   - `pde test pde.yaml <TestName>`

   Do not chain these commands unless you intentionally want a single combined failure output.

3. `pde target install` troubleshooting:

   - If you see `Can't download artifact ... from .../composites/master`, first verify VPN and repository reachability, then retry.
   - Intermittent ECF transfer errors can show up as `InterruptedIOException: Timeout while closing input stream`.
   - If needed, tune ECF transfer behavior with:
     - `org.eclipse.ecf.provider.filetransfer.retrieve.readTimeout`
     - `org.eclipse.ecf.provider.filetransfer.retrieve.retryAttempts`
     - `org.eclipse.ecf.provider.filetransfer.retrieve.closeTimeout`

4. Build with `pde compile`. Some warnings can be ignored.

5. Run product with `pde launch` or run tests with `pde test`.

   Test selection note: `pde test` uses a positional test name (for example `pde test pde.yaml UserDirectoryTest`), not `--name`.

If compilation fails due to missing libraries, look for `lib/fetch_jars` in bundles and inspect the readme there on how to pull extra dependencies.

For all commands, refer to their subcommand `--help`.

# Common warnings

- `Using lowercase profile registry path ... profile.profile`
- `Workspace bundles appear out of date ... run pde compile --execute`
- `Launch plan has unresolved bundles/dependencies; continuing anyway`

These warnings do not always block test execution.

# Configuring tests

Note that for `tests` launches, the `testPluginName` is the main bundle and _not_ the `.tests` fragment bundle.

# Local workspace hygiene

If `pde.yaml` is created only for local issue execution, leave it uncommitted unless explicitly requested.
