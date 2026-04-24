# API Filters: Concrete Implementation Spec

This document defines an implementation-ready contract for reading, writing, and updating Eclipse `.api_filters` files from `pde` tooling.

## Goal

Enable deterministic, scriptable updates to `.api_filters` so API baseline violations can be filtered in headless workflows.

## Non-goals

- Re-implementing PDE's full API problem model.
- Deriving filter IDs from free-form analyzer text.
- Preserving source XML formatting byte-for-byte.

## PDE Ground Truth (must match)

- Filter filename is fixed: `.api_filters`.
- Workspace project location is `.settings/.api_filters`.
- XML root is `<component id="<bsn>" version="2">`; unsupported versions are invalid.
- Structure is:
  - `component`
  - `resource` (`type` optional, `path` optional)
  - `filter` (`id` required numeric, `comment` optional)
  - `message_arguments`
  - repeated `message_argument value="..."`
- Effective matching uses `id`, resource type, ordered message arguments, and optional path semantics.
- Legacy attributes like `severity` / `message` can exist and are ignored by PDE matching.

## Canonical XML Contract Produced By `pde`

`pde` always writes this normalized shape:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<component id="org.example.bundle" version="2">
  <resource path="src/pkg/MyType.java" type="pkg.MyType">
    <filter id="643842064" comment="temporary exception until baseline update">
      <message_arguments>
        <message_argument value="SomeType"/>
        <message_argument value="MyType"/>
        <message_argument value="someMethod()"/>
      </message_arguments>
    </filter>
  </resource>
</component>
```

Writer rules:

- Always emit root `version="2"`.
- Emit `resource` with:
  - `type` when known (recommended required for CLI add).
  - `path` only when available.
- Emit `filter` with:
  - `id` as base-10 signed integer string.
  - `comment` only when non-blank.
- Always emit `message_arguments`; allow zero children.
- Drop legacy attrs (`severity`, `message`) on rewrite (intentional canonicalization).

## In-Memory Model (implementation contract)

```kotlin
data class ApiFilterFile(
  val componentId: String,
  val version: Int = 2,
  val resources: List<ApiFilterResource>
)

data class ApiFilterResource(
  val type: String?,
  val path: String?,
  val filters: List<ApiFilterEntry>
)

data class ApiFilterEntry(
  val id: Int,
  val arguments: List<String>,
  val comment: String?
)
```

Normalized identity keys:

- `ResourceKey = (typeNormalized, pathNormalizedOrNull)`
- `FilterKey = (id, argumentsExactOrder)`
- Full identity for exact remove: `(type, path, id, args[])`

Normalization:

- Trim surrounding whitespace for `componentId`, `type`, `path`, `comment`, and argument values.
- Convert empty strings to `null` for optional fields (`type`, `path`, `comment`).
- Preserve argument order exactly.

## File Resolution Rules

Given a workspace bundle directory `<bundleDir>`:

- Default path: `<bundleDir>/.settings/.api_filters`
- Parent `.settings` is created when writing if missing.
- On create, `componentId` defaults to bundle symbolic name from `MANIFEST.MF`.

Validation:

- If file exists and root `component/@id` does not match expected bundle symbolic name, fail unless `--allow-component-mismatch` is set.
- If file exists and root `version != 2`, fail with actionable error.

## Read Semantics

Parser requirements:

- Strict on structural validity for required fields:
  - root `component/@id` must exist and be non-blank.
  - `filter/@id` must parse as integer.
- Tolerant on unknown elements/attributes (ignore them).
- Missing `message_arguments` is treated as empty arg list.

Output from read:

- Fully normalized `ApiFilterFile` model.
- Duplicate filters are de-duplicated by full identity `(type,path,id,args[])`; first comment wins unless explicit replace mode is requested.

## Update Semantics

### Upsert

Input: `(componentId?, type, path?, id, args[], comment?)`

- Locate resource by `(type,path)`.
- If resource missing: create.
- Within resource, locate filter by `(id,args[])`.
  - If exists: replace comment when provided (or clear when `--clear-comment`).
  - If missing: append.

### Remove

Modes:

- `exact` (default): requires `(type,path?,id,args[])` and removes one exact match.
- `relaxed`: removes all matches for `(type,path?,id)` regardless of args.

If nothing matches:

- Exit code `3` (not found), no file change.

### Sort + Write (deterministic)

Before writing, sort deterministically:

- Resources by `path` (null last), then `type` (null last).
- Filters by `id`, then lexicographic arg tuple, then comment.
- Remove empty `resource` blocks after deletions.

Output encoding:

- UTF-8, newline-terminated, platform-independent `\n`.

## CLI Proposal (concrete)

Add top-level command group:

- `pde api-filters list`
- `pde api-filters add`
- `pde api-filters remove`
- `pde api-filters validate`
- `pde api-filters add-from-report`

### `pde api-filters list`

Example:

```bash
pde api-filters list --bundle-dir org.knime.gateway.api --json
```

Behavior:

- Reads `.settings/.api_filters` and prints normalized entries.
- `--json` emits machine-readable records for tooling.

### `pde api-filters add`

Example:

```bash
pde api-filters add \
  --bundle-dir org.knime.gateway.api \
  --type org.knime.gateway.api.MyType \
  --path src/org/knime/gateway/api/MyType.java \
  --id 643842064 \
  --arg SomeType \
  --arg MyType \
  --arg someMethod() \
  --comment "temporary exception until baseline update"
```

Required flags:

- `--bundle-dir`
- `--type`
- `--id`

Optional flags:

- `--path`
- repeated `--arg`
- `--comment`
- `--dry-run` (print diff/summary only)

Exit codes:

- `0` success (created or updated)
- `2` invalid input
- `4` parse/validation failure

### `pde api-filters remove`

Examples:

```bash
pde api-filters remove \
  --bundle-dir org.knime.gateway.api \
  --type org.knime.gateway.api.MyType \
  --path src/org/knime/gateway/api/MyType.java \
  --id 643842064 \
  --arg SomeType --arg MyType --arg someMethod()
```

```bash
pde api-filters remove \
  --bundle-dir org.knime.gateway.api \
  --type org.knime.gateway.api.MyType \
  --id 643842064 \
  --relaxed
```

Exit codes:

- `0` removed
- `3` no matching filter
- `2` invalid input
- `4` parse/validation failure

### `pde api-filters validate`

Checks only (no write):

- valid XML structure
- root version `2`
- numeric filter IDs
- duplicate full identities

### `pde api-filters add-from-report`

Adds filters by referencing machine-readable `pde api-analyze` results.

Examples:

```bash
pde api-filters add-from-report \
  --report api-analyzer/problems.json \
  --problem P000123
```

```bash
pde api-filters add-from-report \
  --report api-analyzer/problems.json \
  --all \
  --category baseline \
  --severity error \
  --dry-run
```

```bash
pde api-filters add-from-report \
  --report api-analyzer/problems.json \
  --all \
  --category baseline \
  --severity error \
  --apply
```

Required flags:

- `--report <path>`
- one of:
  - repeated `--problem <problemRef>`
  - `--all`

Optional selectors:

- repeated `--bundle <bsn>`
- repeated `--category <name>`
- repeated `--severity <level>`
- `--comment-template <template>`
- `--dry-run` (default behavior when `--apply` absent)
- `--apply` (persist changes)

Behavior:

- Reads problem records from report.
- Selects records by `--problem` and/or selector flags.
- Validates each selected record has required filter material:
  - `bundleBsn`
  - `resourceType`
  - `problemId`
  - `messageArgs` (ordered list, can be empty)
- Performs upsert per selected problem into `<bundleDir>/.settings/.api_filters`.
- De-duplicates by `(type,path,id,args[])`.
- Does not remove existing filters.
- Writes per-bundle summary: `created`, `updated`, `skipped`.

Exit codes:

- `0` success (including no-op with explicit zero selected in dry-run)
- `2` invalid CLI input
- `3` selection resolved to no problems (unless `--allow-empty-selection`)
- `4` report parse/validation failure
- `5` one or more selected problems missing required filter fields

## API Analyze Problem Report Contract

`pde api-analyze` should support:

- `--report <path>`: writes machine-readable problem report JSON.

Recommended shape:

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-04-24T12:34:56Z",
  "tool": "pde api-analyze",
  "problems": [
    {
      "problemRef": "P000123",
      "bundleBsn": "org.example.bundle",
      "resourceType": "org.example.Type",
      "resourcePath": "src/org/example/Type.java",
      "problemId": 643842064,
      "messageArgs": ["SomeType", "Type", "someMethod()"],
      "severity": "error",
      "category": "baseline",
      "message": "Human-readable problem text"
    }
  ]
}
```

Contract notes:

- `problemRef` is stable within one report file and intended for user-facing selection.
- `problemId` and ordered `messageArgs` are the authoritative filter identity payload.
- `resourcePath` is nullable/omittable when analyzer cannot provide path.
- Additional fields are allowed; consumers must ignore unknown fields.

## `pde api-analyze --fix` Convenience Mode

Provide `--fix` as a guarded convenience wrapper around `add-from-report` semantics.

Suggested behavior:

- Analyze projects as normal.
- Build selection default: `category=baseline` and `severity=error`.
- Print proposed filter additions.
- Require `--apply` to actually write; otherwise dry-run.

Suggested flags:

- `--fix`
- `--apply` (required to persist)
- repeated `--fix-bundle <bsn>`
- repeated `--fix-category <name>`
- repeated `--fix-severity <level>`
- `--comment-template <template>`

Safety requirements:

- Never infer `problemId` from free-form text.
- Fail selected items that do not contain required filter fields.
- Never delete filters in fix mode.
- Always print/write operation summary for CI visibility.

## Safe Inputs From `api-analyze`

To automate add/remove from analysis output, we need a machine-readable problem export that includes:

- bundle symbolic name
- stable problem reference (`problemRef`)
- resource type
- resource path (if known)
- filter/problem id (integer)
- ordered message arguments
- severity
- category

Without these fields, especially numeric `id` and ordered args, generated `.api_filters` entries are not reliable.

## Suggested Implementation Steps

1. Add `ApiFiltersCodec` (read/write/normalize/validate) under `core/pde-launch-engine`.
2. Add `ApiFiltersService` for upsert/remove/list operations.
3. Add `pde api-filters` subcommands in launcher CLI and wire to service.
4. Add machine-readable `pde api-analyze --report` output with schema versioning.
5. Add `add-from-report` selector engine and dry-run/apply behavior.
6. Add optional guarded `pde api-analyze --fix` wrapper.
7. Add unit tests with golden XML fixtures (create/read/upsert/remove/sort).
8. Add integration tests for CLI exit codes, report ingestion, and dry-run/apply.
9. Document command usage in `docs/cli-reference.md`.

## Test Matrix (minimum)

- create new file from empty project
- read existing PDE-generated file
- upsert existing `(id,args[])` updates comment only
- remove exact with and without `path`
- remove relaxed removes multiple arg variants
- reject root version `!= 2`
- reject non-numeric `filter/@id`
- deterministic write order stable across runs
- parse and validate `api-analyze --report` schema
- `add-from-report --problem` adds one exact filter
- `add-from-report --all --category baseline --severity error` selection works
- `--dry-run` shows pending changes and does not write files
- `--apply` writes files and emits created/updated/skipped summary
- `--fix` without `--apply` does not write files
- selected problem missing `problemId` or `messageArgs` fails with exit code `5`

## Acceptance Criteria

- Rewriting an existing valid file yields semantically equivalent filters.
- Add/remove operations are deterministic and idempotent.
- CLI returns stable exit codes for automation.
- Resulting files are accepted by PDE API Tools in IDE and headless contexts.
