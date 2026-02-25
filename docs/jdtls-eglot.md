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
- When you run from an issue root that contains `config.yaml`, `jdtls-init` defaults
  `--issue-dir` to the current directory and writes
  `./.jdtls-data/projectConfigurations.json` if you omit `--project-configurations-out`.
- Files are written per bundle directory and always overwrite existing metadata.
- `--project-configurations-out` emits `rootPaths` and `workspaceFolders` alongside project configurations
  for editor integrations that need explicit workspace roots.
- `jdtls-init` requires a `target` section in `config.yaml` with a resolved profile.
- For issue-dir layouts the default profile path resolves from the issue root,
  e.g. `<issue>/target/p2/org.eclipse.equinox.p2.engine/profileRegistry/Profile.profile`.


### Verification

Manual check with `jq` (JSON key is `.projectConfigurations`):

```bash
jq '.projectConfigurations | map(.projectName)' projectConfigurations.json
```

## Eglot setup (Spacemacs + Projectile, issue-dir)

1) In the issue dir: `touch .projectile`.
2) Generate metadata: `pde jdtls-init` (writes `./.jdtls-data/projectConfigurations.json`).
3) Add this to `~/.spacemacs` to pass `projectConfigurations` at initialization:

```elisp
(defun ben/jdtls--issue-root ()
  (or (locate-dominating-file default-directory ".jdtls-data")
      (locate-dominating-file default-directory "config.yaml")
      (when-let ((project (project-current nil)))
        (project-root project))))

(defun ben/jdtls--project-configurations (issue-root)
  (let ((config-file (and issue-root
                          (expand-file-name ".jdtls-data/projectConfigurations.json" issue-root))))
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

- `java.project.import` (use `projectConfigurations.json` from `jdtls-init`)
- `java.project.refresh`
- `java.project.updateProjectConfiguration`
- `java.project.refreshDiagnostics`
