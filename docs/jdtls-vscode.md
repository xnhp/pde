# JDT LS with VS Code

Unlike Eglot, VS Code's Java tooling ("Language Support for Java(TM) by Red Hat",
extension id `redhat.java`) always runs its own bundled JDT LS — there is no setting
to point it at an external LS executable, so `pde lsp run` is not used here. What
matters instead is getting *its* JDT LS to import our generated `.project`/`.classpath`
via Eclipse project import rather than trying Maven/Gradle import first.

## Setup

1. Install the `redhat.java` extension in VS Code.
2. Build the `pde` CLI if needed (`./gradlew :pde-cli:installDist`).
3. Run `pde ide-init vscode` from the issue root (or `--issue-dir`/`--config` as usual).
   This writes a `.code-workspace` multi-root file, plus `.vscode/tasks.json` and
   `.vscode/launch.json` (only if absent — never clobbers your own) for the
   `launches:`/`tests:` entries in `pde.yaml`, covered in detail below. It does not
   generate any JDT LS project metadata.
4. Run `pde lsp init` from the same root. This runs the same `pde jdt-workspace init`
   Equinox app used by `pde api-baseline check`/`pde jdt-workspace build` to materialize
   real `.project`/`.classpath` files directly at each bundle's own directory (visible-mode
   project placement — VS Code's bundled JDT LS has no way to consume a shared,
   invisible-project workspace the way `pde api-baseline check --workspace-data` does, so
   it needs real files on disk). `pde lsp init` is shared with the Eglot flow (see
   [jdtls-eglot.md](jdtls-eglot.md)) — nothing about project-file generation is VS
   Code-specific. It also writes two more things:

   - `.vscode/settings.json` (only if it doesn't already exist — never clobbers
     your own settings) with:
     ```json
     {
       "java.import.maven.enabled": false,
       "java.import.gradle.enabled": false
     }
     ```
     This is necessary because bundles with a Tycho `pom.xml` (common in PDE workspaces)
     would otherwise be picked up by the Maven importer first, ignoring the
     target-platform-resolved classpath `lsp init` wrote. Disabling both importers falls
     the extension back to Eclipse project import, which scans for `.project` files.
   - `.projectile` (same as the Eglot flow), harmless for VS Code.

5. Open the issue directory as a VS Code workspace folder (`code /path/to/issue-dir`).
   The Java extension should pick up the generated projects automatically on open;
   if not, run **Java: Clean the Java language server workspace** or **Java: Import
   Java Projects** from the command palette.

## Running and debugging launches/tests from VS Code

There is no custom VS Code extension for `pde` — everything below is generated
into standard `tasks.json`/`launch.json` so the built-in Run/Debug UI (backed by
the `redhat.java`/`vscode-java-debug` extensions) can drive it.

For each `launches:` entry in `pde.yaml`, `ide-init vscode` writes:

- A plain task (label = the entry's `name`) that runs `pde run <config> <name>`.
- A `<name> (debug)` task that runs `pde run <config> <name> --debug`. This opens
  real JDWP on port 5005 and blocks with `Waiting for debugger to attach on port
  5005...` until a debugger attaches; the task is marked `isBackground: true` with
  a problem matcher whose `endsPattern` matches that exact line, so VS Code knows
  once the process is ready to attach to.
- A matching `attach` configuration in `launch.json` (`hostName: localhost`, `port:
  5005`) whose `preLaunchTask` points at the `(debug)` task above by label.

For each `tests:` entry, it writes a `test: <name>` task running `pde test <config>
<name>` (nameless entries fall back to their 1-based index, the same
index-or-name resolution `pde test` itself accepts). **Only entries with `debug:
true` set in their `pde.yaml` test entry** get a `test: <name> (debug)` task and
matching `launch.json` attach configuration — `pde test --debug` is a log
verbosity flag, not JDWP, so the debug-task variant runs the exact same plain
`pde test <config> <name>` command; it is `debug: true` on the YAML entry itself
that makes the launch engine wait for a debugger on port 5005 before running the
test.

To use these:

- **Debugging** (either a launch or a `debug: true` test entry): open the Run and
  Debug panel and pick the corresponding `... (debug)` configuration. VS Code runs
  the background task first, waits for the `Waiting for debugger...` line, then
  attaches automatically.
- **Running a plain launch or test without debugging**: run its (non-`(debug)`)
  task via **Tasks: Run Task** from the command palette. There's no way to give an
  arbitrary shell command its own button in the Run and Debug panel outside of a
  debug attach target, so non-debug launches/tests are only reachable through the
  Tasks UI, not the Debug dropdown.

Limitations:

- The debug port (5005) is fixed; there's no `pde` flag to change it, so only one
  JDWP debug session (one launch or debuggable test) can run at a time. This is an
  accepted v1 limitation, not a bug.
- `tasks.json`/`launch.json` are only written the first time (same non-clobber
  rule as `settings.json`): if `launches:`/`tests:` entries change afterward,
  re-running `pde ide-init vscode` will *not* pick up the change automatically —
  delete the generated file(s) (or edit them by hand) and re-run.

## Caveats

- VS Code's bundled JDT LS is a different, extension-pinned build from the one
  `pde lsp run`/Eglot uses (`~/.cache/pde/jdtls/<version>`). Behavior should be
  equivalent (same underlying `EclipseProjectImporter`/classpath model) but the
  exact version may differ.
- Because there's no external-LS hook, none of `pde lsp run`'s fail-fast/`--download`
  logic applies here — the extension manages its own JDT LS download/update.
- Same gaps as documented in [jdtls-eglot.md](jdtls-eglot.md#what-jdt-ls-diagnostics-do-not-cover):
  no OSGi/PDE-level diagnostics, since the bundled JDT LS also ships without
  `org.eclipse.pde.core`.
- Re-run `pde lsp init` whenever workspace bundles change (re-running `pde ide-init
  vscode` is only needed if the set of bundles/workspace folders themselves changed).
  VS Code's JDT LS has the same `.project`/`.classpath`/`.settings/*.prefs` file watcher
  as documented for Eglot, so it should reimport automatically once those files change.
- **VS Code's JDT LS and `pde jdt-workspace build` share compiled output, but not build
  state.** Because projects are visible-mode (`.classpath`'s output entry is a real path
  under the bundle directory, e.g. `<bundleDir>/bin`), both VS Code's bundled JDT LS and
  `pde jdt-workspace build` write `.class` files to the *same physical directory* when
  building the same bundle. But each tracks its own incremental-build state separately,
  inside its own Eclipse workspace (`-data` directory) — VS Code's bundled JDT LS keeps
  its own internal workspace storage, entirely separate from `pde jdt-workspace build`'s
  `.jdtls/workspace/data`, and JDT's incremental builder decides what to recompile from
  that persisted state, not from inspecting whether matching `.class` files already exist
  on disk. So the first time *either* one builds a given project, it does a real build
  regardless of whether the other already compiled that exact code — it will just
  overwrite whatever's already in the output folder, not recognize it as current. There is
  currently no way to point VS Code's bundled JDT LS at a different `-data` directory
  (checked the full `redhat.java` settings surface — nothing exposes it), so this isn't
  fixable by configuration; sharing build state would require a version-pinned fork of the
  `vscode-java` extension, which was evaluated and not pursued (narrow benefit — only
  skips the *first* full build after a cold open — against the ongoing cost of keeping a
  forked extension's bundled JDT LS version in lockstep with `pde`'s pinned one).
- **`pde compile` can also write to that same output directory**, and unlike the two JDT
  builders above, it can actively *delete* files there: on a full rebuild it clears the
  output directory first, based on its own separate `BundleCompileCache` fingerprint
  tracking, which has no awareness of VS Code's or `pde jdt-workspace build`'s state
  either. Interleaving `pde compile` with either JDT builder against the same bundle isn't
  destructive (everything is regenerable from source), but each can invalidate the
  other's just-finished output with no coordination between them.
