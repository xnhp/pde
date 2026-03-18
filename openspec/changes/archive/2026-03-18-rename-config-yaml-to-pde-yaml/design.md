## Context

The PDE tooling currently treats `config.yaml` as the default configuration filename for headless workflows. This filename appears in file discovery logic, user-facing error/help messages, documentation examples, and automated tests. The proposed change is intentionally breaking: `pde.yaml` becomes the only supported filename, with no fallback lookup and no migration compatibility layer.

## Goals / Non-Goals

**Goals:**
- Make `pde.yaml` the canonical and required configuration filename for configuration discovery paths.
- Keep behavior consistent across all affected tools and shared libraries.
- Ensure user-facing guidance (errors, docs, examples) consistently references `pde.yaml`.

**Non-Goals:**
- Supporting both filenames during a transition window.
- Automatic migration, rename utilities, or compatibility warnings for legacy filenames.
- Broader configuration schema or semantic changes beyond filename resolution.

## Decisions

- Replace filename constants/usages from `config.yaml` to `pde.yaml` in shared config-loading paths and all entry points that rely on default discovery.
  - Rationale: centralizing filename handling in existing discovery points minimizes drift between CLI tools and keeps change scope explicit.
  - Alternative considered: support both names with precedence (`pde.yaml` first, then `config.yaml`). Rejected because requirement explicitly disallows backward compatibility.
- Update all externally visible references to the filename in docs, examples, tests, and error text.
  - Rationale: the change is user-facing and breaking; incomplete messaging creates avoidable support friction.
  - Alternative considered: update only runtime behavior and leave docs/tests for follow-up. Rejected because it introduces inconsistent contracts.

## Risks / Trade-offs

- [Risk] Existing users with `config.yaml` will see immediate failures after upgrade. -> Mitigation: clear error messages that state the required `pde.yaml` filename.
- [Risk] Missed hard-coded references to `config.yaml` may cause partial behavior divergence across modules. -> Mitigation: repository-wide search and targeted tests for config discovery in each app.
- [Trade-off] Faster simplification now versus smoother user transition. -> Mitigation: accept explicit breaking change as requested and keep change narrowly scoped.
