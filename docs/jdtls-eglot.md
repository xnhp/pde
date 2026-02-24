# JDT LS with Emacs (eglot)

This repo can generate PDE-friendly `.project`/`.classpath` files for JDT LS via `pde jdtls-init`.
Eglot can use those directly; no extra protocol integration is required.

## Generate workspace metadata

1. Build the `pde` CLI if needed:

```bash
./gradlew :pde-launch:installDist
```

The binary lives at `apps/pde-launch/build/install/pde/bin/pde`.

2. Run `jdtls-init` from the issue root (defaults) or point at a config:

```bash
pde jdtls-init
pde jdtls-init --issue-dir /path/to/workspace
pde jdtls-init --config /path/to/config.yaml
pde jdtls-init --issue-dir /path/to/workspace \
  --project-configurations-out /path/to/workspace/.jdtls-data/projectConfigurations.json
```

`jdtls-init` discovers `config.yaml`, `launch.yaml`, or `pde.yaml` if you omit `--config`.

Notes:
- Re-run `jdtls-init` when workspace bundles change.
- Use `--project-configurations-out` to write `projectConfigurations.json` next to the
  per-issue `-data` dir; JDT LS uses this to import projects after updates.
- When you run from an issue root that contains `config.yaml`, `jdtls-init` defaults
  `--issue-dir` to the current directory and writes
  `./.jdtls-data/projectConfigurations.json` if you omit `--project-configurations-out`.
- Files are written per bundle directory and always overwrite existing metadata.
- When using `--issue-dir`, bundle paths in `bundlesPerRepo` resolve relative to the issue directory
  even if they come from included YAML files (includes still resolve relative to their file).
- `--project-configurations-out` emits `rootPaths` and `workspaceFolders` alongside project configurations
  for editor integrations that need explicit workspace roots.
- `jdtls-init` requires a `target` section in `config.yaml` with a resolved profile (set `target.profile-id` and `target.p2-path`, and run `pde target-install` as needed).
- For issue-dir layouts the default profile path resolves from the issue root, e.g. `<issue>/target/p2/org.eclipse.equinox.p2.engine/profileRegistry/Profile.profile`.

## Eglot setup

Add a JDT LS entry with a stable workspace data directory:

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               `(java-mode . ("jdtls" "-data" ,(expand-file-name "~/.cache/jdtls/workspaces/your-workspace")))))
```

If Eglot chooses the wrong project root, make sure it points at the issue root
(the directory with your config). In multi-repo setups, add a local project marker
and open files through that project.

### Issue-dir layouts and root selection

When you use `pde jdtls-init --issue-dir`, the workspace root is the issue directory
that contains `config.yaml` and the repo checkouts. JDT LS metadata is written under
that issue root, and `bundlesPerRepo` paths resolve relative to it.

Eglot often defaults to the repo root of the current file. In an issue-dir layout,
override the project root so it matches the issue directory; otherwise JDT LS will
miss the generated metadata or resolve the wrong bundle paths.

Options that work well:

```elisp
(with-eval-after-load 'project
  (add-to-list 'project-vc-extra-root-markers "config.yaml")
  (add-to-list 'project-vc-extra-root-markers "pde.yaml")
  (add-to-list 'project-vc-extra-root-markers "launch.yaml"))
```

If you use Spacemacs/Projectile, a `.projectile` file in the issue root is often
enough to force the correct root. Alternatively, register the issue root once via
`M-x project-remember-projects-under` and open files through that project.

Optional per-workspace settings (via `.dir-locals.el`):

```elisp
((java-mode . ((eglot-workspace-configuration
                . ((:java . (:configuration (:updateBuildConfiguration "automatic"))))))))
```

## Import with projectConfigurations.json

When you keep JDT LS state in a per-issue `-data` directory, also keep the
`projectConfigurations.json` file there. After re-running `jdtls-init`, import
the updated project list and refresh diagnostics.

Minimal helper that reads `projectConfigurations.json` and runs `java.project.import`
(run from a Java buffer so `eglot-current-server` is non-nil):

```elisp
(defun pde-jdtls-import (config-file)
  "Import JDT LS projects from projectConfigurations.json."
  (interactive (list (expand-file-name "projectConfigurations.json"
                                       "~/issues/<issue>/.jdtls-data")))
  (let* ((json-object-type 'plist)
         (payload (json-read-file config-file)))
    (eglot-execute-command (eglot-current-server)
                           "java.project.import"
                           payload)))
```

Manual check with `jq` (JSON key is `.projectConfigurations`):

```bash
jq '.projectConfigurations | map(.projectName)' projectConfigurations.json
```

Workflow for per-issue data dirs:

```text
1. pde jdtls-init (from the issue root; writes ./.jdtls-data/projectConfigurations.json)
2. M-x pde-jdtls-import (from a Java buffer in the issue project)
3. M-x eglot-execute-command -> java.project.refreshDiagnostics
```

## Slow real-workspace smoke tests

This section moved to `docs/jdtls-smoke-tests.md`.


## Common JDT LS commands

If imports or diagnostics look stale, run these via `M-x eglot-execute-command`:

- `java.project.import` (use `projectConfigurations.json` from `jdtls-init`)
- `java.project.refresh`
- `java.project.updateProjectConfiguration`
- `java.project.refreshDiagnostics`
