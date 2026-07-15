# JDT LS with Emacs (eglot)

This repo can generate PDE-friendly `.project`/`.classpath` files for JDT LS via `pde lsp init`.
Eglot can use those directly; no extra protocol integration is required.

## Generate workspace metadata

1. Build the `pde` CLI if needed:

```bash
./gradlew :pde-cli:installDist
```

The binary lives at `apps/pde-launch/build/install/pde/bin/pde`.

2. Run `lsp init` from the issue root (defaults) or point at a config:

```bash
pde lsp init
pde lsp init --issue-dir /path/to/workspace
pde lsp init --config /path/to/pde.yaml
pde lsp init --issue-dir /path/to/workspace \
  --project-configurations-out /path/to/workspace/.lsp/projectConfigurations.json
```

`lsp init` discovers `pde.yaml`, `launch.yaml`, or `pde.yaml` if you omit `--config`.

Notes:
- Re-run `lsp init` when workspace bundles change (see "Live reimport" below for why
  a manual re-run is usually enough — you don't need to also trigger `java.project.import`).
- When you run from an issue root that contains `pde.yaml`, `lsp init` defaults
  `--issue-dir` to the current directory and writes
  `./.lsp/projectConfigurations.json` if you omit `--project-configurations-out`.
- Files are written per bundle directory and always overwrite existing metadata.
- `--project-configurations-out` emits `rootPaths` and `workspaceFolders` alongside project configurations
  for editor integrations that need explicit workspace roots.
- `lsp init` requires a `target` section in `pde.yaml` with a resolved profile.
- For issue-dir layouts the default profile path resolves from the issue root,
  e.g. `<issue>/target/p2/org.eclipse.equinox.p2.engine/profileRegistry/Profile.profile`.

## Running JDT LS: `pde lsp run`

`pde lsp run` is a thin wrapper an editor can spawn directly as the LSP server
command. It runs the same metadata generation as `lsp init`, resolves a JDT LS
distribution, then execs JDT LS with stdio connected straight through to the
editor.

By default it does **not** download anything: if no matching JDT LS
distribution is already cached under `~/.cache/pde/jdtls/<version>`, it fails
fast with an error telling you to pass `--download` or `--jdtls-home`. Pass
`--download` to bootstrap (fetch + cache) a distribution the first time; after
that it's reused automatically. Pin a specific release with
`JDTLS_VERSION`/`JDTLS_BUILD`/`JDTLS_URL` (or `-Djdtls.version=`/`-Djdtls.build=`/`-Djdtls.url=`
JVM properties, set *before* `-jar` if invoking the `java` command directly).

```bash
pde lsp run --download          # first run: fetch + cache, then launch
pde lsp run                     # subsequent runs: reuse the cache, fail fast if missing
pde lsp run --issue-dir /path/to/workspace
pde lsp run --config /path/to/pde.yaml --data-dir /path/to/workspace/.lsp
pde lsp run --jdtls-home ~/tools/jdt-language-server-1.56.0   # skip cache entirely
```

Options: `--config`, `--issue-dir`, `--data-dir` (defaults to `<issue-dir>/.lsp`),
`--jdtls-home`, `--download`.

### Live reimport

JDT LS registers a file watcher for `**/.project`, `**/.classpath`, and
`**/.settings/*.prefs` out of the box (`StandardProjectsManager.basicWatchers`).
Any LSP client that forwards filesystem change notifications for dynamically
registered watchers (Eglot does, as a generic LSP feature) will cause JDT LS to
reimport automatically once `lsp init`/`lsp run` rewrites those files — no
explicit `java.project.import` call needed after the first import. There is
still no watcher that triggers `lsp init` itself when `pde.yaml`/manifests
change; you (or your editor tooling) need to re-run it.

### What JDT LS diagnostics do *not* cover

JDT LS ships without the PDE plugin (`org.eclipse.pde.core`), so the
`org.eclipse.pde.ManifestBuilder`/`org.eclipse.pde.SchemaBuilder` build commands
in the generated `.project` are inert — Eclipse silently no-ops build commands
it can't resolve. In practice this means editing under JDT LS gives you real
Java-level diagnostics (types, compile errors) but no OSGi/PDE-level diagnostics
(unresolved `Require-Bundle`, singleton conflicts, `Import-Package` version-range
mismatches, `plugin.xml` extension-point schema errors). Those stay the domain
of `pde api-baseline check`/`pde target health`/a real PDE build, run separately.

This does **not** affect cross-bundle navigation (`textDocument/definition`,
`references`, `implementation`, type hierarchy, `workspace/symbol`) — those are
plain Java classpath-model features keyed off the `.classpath` entries `lsp init`
already resolves correctly from the OSGi manifests, and are exercised end-to-end
by the JDT LS smoke tests (`JdtlsSmokeTest.kt`).

## Compilation outputs and `pde compile`

`pde lsp init` only generates metadata; it does not compile. It sets the Java
output directory based on `build.properties` (`output..`) or falls back to `bin`.
JDT LS handles compilation and incremental updates after import, independent of
`pde compile`.

`pde compile` is a separate ECJ-based compile step that writes bundle output
folders and can emit runtime layouts. There is no file-change watcher that
triggers `pde compile` automatically.

### Using JDT LS outputs for `pde run`

`pde run` expects compiled classes in the class roots configured for each
workspace bundle (see schema description for `bundles[].classRoots`).
Those roots must match the output directory that JDT LS is writing to. If a
bundle uses `build.properties` with `output..=bin/eclipse` and your config
still uses `classRoots: [bin]`, `pde run` will report missing classes.

JDT LS only writes `.class` files. It does not copy non-Java resources into the
output directory. This is fine for interactive `pde run` because the bundle
location is still the module directory, but it is not a drop-in replacement for
`pde compile` when generating runtime layouts or bundles.info.


### Verification

Manual check with `jq` (JSON key is `.projectConfigurations`):

```bash
jq '.projectConfigurations | map(.projectName)' projectConfigurations.json
```

## Eglot setup (Spacemacs + Projectile, issue-dir)

1) In the issue dir: `touch .projectile`.
2) Generate metadata: `pde lsp init` (writes `./.lsp/projectConfigurations.json`).
3) Add this to `~/.spacemacs` to pass `projectConfigurations` at initialization:

```elisp
(defun ben/jdtls--issue-root ()
  (or (locate-dominating-file default-directory ".lsp")
      (locate-dominating-file default-directory "pde.yaml")
      (when-let ((project (project-current nil)))
        (project-root project))))

(defun ben/jdtls--project-configurations (issue-root)
  (let ((config-file (and issue-root
                          (expand-file-name ".lsp/projectConfigurations.json" issue-root))))
    (when (and config-file (file-exists-p config-file))
      (let ((json-object-type 'plist)
            (json-array-type 'vector)
            (json-key-type 'keyword))
        (json-read-file config-file)))))

(defun ben/jdtls--init-options ()
  (let* ((issue-root (ben/jdtls--issue-root))
         (data (and issue-root (ben/jdtls--project-configurations issue-root)))
         (project-configurations (and data (plist-get data :projectConfigurations))))
    (when project-configurations
      (let ((root-paths (plist-get data :rootPaths)))
        (append
         (list :projectConfigurations project-configurations)
         (when root-paths
           (list :rootPaths root-paths)))))))

(defun ben/jdtls--java-server-p (server)
  (seq-some (lambda (pair)
              (memq (car pair) '(java-mode java-ts-mode)))
            (eglot--languages server)))

(cl-defmethod eglot-initialization-options :around ((server eglot-lsp-server))
  (let ((base (cl-call-next-method)))
    (if (ben/jdtls--java-server-p server)
        (or (ben/jdtls--init-options) base)
      base)))
```

4) Open a Java file under the issue root, then run `M-x eglot-reconnect`.
5) Verify import:

```elisp
(eglot-execute-command (eglot-current-server) "java.project.getAll" '())
```


## Common JDT LS commands

If imports or diagnostics look stale, run these via `M-x eglot-execute-command`:

- `java.project.import` (use `projectConfigurations.json` from `lsp init`)
- `java.project.refresh`
- `java.project.updateProjectConfiguration`
- `java.project.refreshDiagnostics`
