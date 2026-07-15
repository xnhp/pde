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

This writes a `.code-workspace` multi-root file, then runs the same `pde workspace
setup` Equinox app used by `api-analyze`/`jdt-build` to materialize real
`.project`/`.classpath` files directly at each bundle's own directory (visible-mode
project placement — VS Code's bundled JDT LS has no way to consume a shared,
invisible-project workspace the way `pde api-analyze --workspace-data` does, so it
needs real files on disk). It also writes two more things relevant to VS Code:

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
  target-platform-resolved classpath `ide-init vscode` wrote. Disabling both importers
  falls the extension back to Eclipse project import, which scans for `.project` files.
- `.projectile` (same as the Eglot flow), harmless for VS Code.

4. Open the issue directory as a VS Code workspace folder (`code /path/to/issue-dir`).
   The Java extension should pick up the generated projects automatically on open;
   if not, run **Java: Clean the Java language server workspace** or **Java: Import
   Java Projects** from the command palette.

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
- Re-run `pde ide-init vscode` whenever workspace bundles change; VS Code's JDT LS has the
  same `.project`/`.classpath`/`.settings/*.prefs` file watcher as documented for
  Eglot, so it should reimport automatically once those files change.
