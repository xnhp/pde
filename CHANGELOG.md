# Changelog

## v0.2.0 - 2026-08-26

### Bug Fixes
- **jdt-workspace**: refresh the workspace from disk before the incremental build (2744520)
- **api-baseline**: report absent non-optional dependencies as a warning, not a hard failure (6436d1d)
- **api-baseline**: fail loudly instead of degrading when the API description is incomplete (65c6b83)
- **api-baseline**: reword unresolved-constraints problem so it does not claim the analysis aborted (a8d7725)
- **cli-tests**: keep exitProcess out of the test JVM so PdeCliTest runs to completion (42e638c)
- **cli**: advertise only the options jdt-workspace build/init accept (140f13c)
- **pde-resolver**: compile org.eclipse.pde.api.tools against the pinned runtime jar (402af3a)
- **compile**: warn instead of failing when annotation processors are on the classpath (#160) (bc30be6)
- collapse CI/build/chore changelog sections, exclude merge commits, reclassify v0.1.0 entries (4cfc9ea)

### Documentation
- **api-baseline**: mention unused-filter (usage) findings in check --report and prune help (26626a0)

### Features
- **jdt-workspace**: refuse to open a JDT workspace another pde process is using (00e01d8)
- **target-install**: fail early when update sites are unreachable (9d28bd5)
- **api-filters**: edit .api_filters in place and add --dry-run to prune/add-all-from-report (2231c52)
- **jdt-workspace**: report compile errors on stdout and fail the build (13bdf71)
- **target-install**: print coarse progress by default, live redraw only with --interactive (3283299)
- migrate `issue fetch_jars` to `pde fetch-jars` (357f10d)
- **ide-init**: emit module-library orderEntries for Bundle-ClassPath jars (b5d2da8)

### Performance
- **jdt-workspace**: save workspace state after build and reuse the extracted runtime (d4b6b7f)

## v0.1.0 - 2026-07-27

### Breaking Changes
- Default generated/output directory changed from `target/` to `.target/` (e.g. `target/p2`,
  `target/install`, `target/bundle-pool` -> `.target/p2`, `.target/install`, `.target/bundle-pool`).
  Projects relying on the previous default should either set `target.p2Path`, `target.install`,
  and `target.bundlePool` explicitly in `pde.yaml` to keep using an existing `target/` directory,
  or rename/regenerate the directory as `.target/`.

### Bug Fixes
- create p2Path directory before opening the profile registry (7a813e1)
- refresh reused workspace projects in WorkspaceSetupService (32671de)
- remove dry-run behaviour from api-baseline filters prune/add-all-from-report (83292ad)
- also skip api-baseline/component-resolution/fatal categories in add-all-from-report (909bf1b)
- api-baseline reports written without .json extension miss add-all-from-report discovery (c5779d9)
- align .api_filters matching with Eclipse PDE semantics (d9b474a)
- suppress false-positive system.bundle Require-Bundle warning in API baseline diagnostics (1cf00cb)
- vscode code-workspace uses bundle paths instead of VCS roots (a0cdc15)
- handle CompositeApiDescription wrapper in refreshPackages reflection (c794eb4)
- list `pde target install api-baseline` in the --help command tree (36d6a23)
- workspace setup's target classpath now expands Bundle-ClassPath entries (7441af2)
- align pde ide-init vscode with jdt-workspace/api-baseline rename, fix stale test assertion (6170790)
- clarify help text for pde schema subcommand (43c09c4)
- discoverConfigFile walks parent directories for auto-discovery (300d6c6)
- disable auto-build in workspace setup to prevent JDT NPE (5fba12a)
- enable since-tag detection for workspace ProjectComponents (fd4cbe6)
- restore ProjectComponent with detached-state description override (db99203)
- remove ProjectComponent usage to avoid OSGi state ownership conflict (39c6484)
- match pde.api.tools compile dependency version to runtime (1.3.600) (946516a)
- point api-analyze Equinox -data to workspace setup directory when --workspace-data is provided (7a4da64)
- link META-INF at project root and add project classpath entries for workspace deps (8101335)
- exclude bundle root from classpath entries to avoid JDT nesting error (29980ca)
- correct application IDs and add required runtime bundles (4402b56)
- make workspaceSetupMain, EquinoxAppInvocation, EquinoxAppRuntime public (ebaed39)
- **lsp**: use cli-core logging in lsp init (e6f3cf6)
- **jdtls**: skip non-Java bundles from .project/.classpath generation (afe978e)
- **jdtls**: rename generated .jdtls-data folder to .lsp (3670c9d)
- **api**: walk Import-Package/Require-Capability edges in target dependency closure (38266b7)
- **api**: drop conflicting singleton dependency-scope artifact from baseline/current merge (53b9352)
- **api**: pull Fragment-Host closure into api-analyze dependency set (9179dbb)
- **api**: compute Require-Bundle diagnostics across current+dependency scope (2b8a1ea)
- **api**: include every target version needed by Require-Bundle ranges (d00b28b)
- **api**: update stale --log help text and log the batch launch (fb2a0bf)
- **api**: drop the legacy single-bundle analyzer input format (1550f3e)
- **api**: actually batch pde api-analyze into one analyzer JVM (405c97b)
- **api**: harden direct analyzer launch (7233c2e)
- **api**: clean analyzer shutdown (d7279bf)
- **target**: rebuild index from artifact metadata (4bd884e)
- **intellij**: run library root updates on EDT (133ceb2)
- **target**: use uppercase profile registry path (9f5b61b)
- **target**: fail when installer plugin is missing (68a7253)
- **target**: fail fast when Eclipse SDK is missing (da3c497)
- preserve launch args as atomic (043f4ef)
- discover test config with option-only args (c5cf5b3)

### Build System
- **ci**: authenticate build.yml against GitHub Packages (41ac2ec)
- **ci**: make pinned runtime bundle lockfiles reproducible from a clean SDK (884256f)
- **ci**: provision an Eclipse SDK on CI and decouple check from Equinox runtimes (b042d41)
- support eclipseLauncherJar property, make publishSdkP2Repo inputs optional, download just launcher jar on CI (6ea6fd4)
- download full Eclipse SDK on CI for materialization tasks and pinned bundle cache (10fcf4e)
- exclude publishSdkP2Repo from release build (minimal SDK lacks full Eclipse SDK) (9875503)
- also create features/ directory in minimal Eclipse SDK for publishSdkP2Repo validation (871e834)
- provide minimal Eclipse SDK on CI and remove eager launcher jar input from materializeRuntime (a7d3d24)
- release workflow full checkout depth and lazy equinox launcher resolution (88f946a)
- align GITHUB_OUTPUT heredoc delimiter quotes in release workflow (74d077f)
- remove leaked test artifacts and embedded repos (db1de48)
- **api**: replace api-analyzer/target-installer Exec scripts with Gradle-native tasks (61b451f)
- **target**: use javac argfile for installer compilation (8a4af16)
- **target**: build target-installer for Java 21 (72f21fc)
- include cli-core only via cliCorePath (0a17743)
- update changelog for v0.1.0 release (018cc72)
- remove pde add-test-helper CLI subcommand (ca745e7)
- gitignore stale Claude worktrees (5936806)
- automate release versioning (a48b1c5)
- lint pull request head commits (e4c1c39)
- add release workflow (8523275)
- resolve cli-core via GitHub Packages (5246b82)
- run linux only (c2b5e8c)

### Documentation
- document that VS Code JDT LS and pde jdt-workspace build share output but not build state (24b74f7)

### Features
- add --version/-V flag to pde CLI (3ccadb7)
- expose api-baseline filters prune/regroup in the picocli command tree (22401b9)
- add `pde api-baseline filters prune`, regroup filter commands under `filters` (978244b)
- default 'pde jdt-workspace build' --data to init's output path (0d2940f)
- add pde lsp ignore/unignore to skip-worktree .project/.classpath/.prefs (67ac7ad)
- add --allow-missing-fields to api-baseline add-all-from-report (ab4a6b5)
- actually apply .api_filters suppression in direct API analyzer (0f1ad21)
- warn in ide-init when a bundle's fetch_jars lib folder has no jars (494dc71)
- add pde init-config command; fix modules.xml template (b40d2d1)
- remove non-functional staleness check from pde compile (4ae2e87)
- move api-filters to api-baseline, add add-filter command (14a571e)
- generate VS Code tasks.json/launch.json for launches/tests (d792725)
- render pde --help command tree with unicode box-drawing guides (4f59549)
- switch WorkspaceSetupService to visible-mode, fold JDT LS project files into pde ide-init vscode (2c568a9)
- rework api-analyze CLI surface as api-baseline (0065b3f)
- render full nested command tree for top-level pde --help (b2c3bcd)
- change default target output directory from target/ to .target/ (cae13d7)
- add CLI commands for workspace-setup, jdt-build, and api-analyze workspace support (e939d66)
- bundle Eclipse JDT formatter JARs with pde-format, removing runtime Eclipse dependency (90c7c35)
- add JDT build Equinox application (48fe840)
- add ProjectComponent support to api-analyze for since-tag checks (84b22b6)
- add workspace-setup Equinox application (72c3ee3)
- add WorkspaceProjectSpec and WorkspaceSetupInput JSON contracts (383e123)
- **jdtls**: write .project/.classpath into .jdtls-data/projects/ instead of bundle directories (d9c23f6)
- **lsp**: consolidate JDT LS init/run under `pde lsp`, add `pde ide-init vscode` (cc6873a)
- **api**: launch packaged analyzer runtime (d3f789c)
- **api**: default to direct analyzer app (80f84d8)
- **api**: route direct analyzer through input manifests (52c7da6)
- **api**: add direct API analyzer runtime (b63e9b7)
- **target**: add bundle pool health repair commands (19b1903)
- **config**: gate bundle whitelist behavior (7af06e8)
- **target**: trust all p2 authorities on opt-in (e960b58)

### Other
- Revert "fix: refresh reused workspace projects in WorkspaceSetupService" (10c8dbc)
- fix prune: downgrade 'could not locate stale filter' from WARNING to INFO (be501d1)
- fix prune: handle inner-class type mismatch and single-candidate fallback (5e74d93)
- fix prune: use substring containment for unused filter arg matching (69dba70)
- compat with current IJ version (a7900e9)
- no recover workspace (0a1b233)
- Rename pde workspace/jdt-build commands to jdt-workspace group (f500511)
- test improvements (e1df93d)
- API analysis batch workspace bundles in one analyzer JVM (9131365)
- Update skill with info on api analysis (76327e4)
- Install org.eclipse.osgi.services only when present (be0d7ac)
- remove openspec artifacts (1fab5d7)
- Update target installer README (#133) (b7cad64)
- Fix CI checks for knime (#66) (aa3a598)
- Document JaCoCo coverage workflow (#110) (#129) (61e33b2)
- Use cli-core color policy for maturity tags (#130) (53a7f04)
- Deduplicate pde CLI helpers (#110) (437178b)
- Clean up pde help output (#124) (#126) (94b852a)
- Package target installer launcher (#119) (d4159ce)
- Share target configure-phase parsing (#122) (1b154f9)
- Model target install inputs (#120) (6678ccf)
- Document target installer boundaries (#101) (2315814)
- Add validate-config command (#115) (1dabd1b)
- Warn on duplicate YAML keys (#112) (f124de9)
- Handle CRaC checkpoint exit code (#109) (b1dfb6e)
- Use envFile JAVA_HOME for launches (80840ef)
- Clarify PDE config-driven MCP tools (33b8937)
- Add target pinned versions support (6a9d8c4)
- Fix IDEA module roots (535d9d4)
- Use issue IDEA project root (f07f3cb)
- Improve IDEA project initialization (f1357c9)
- Log active target extra bundles (b440d5a)
- Add target extraBundles resolution (ddf9d81)
- Validate launch env variable names (51ccd54)
- Use p2 profile directories for IDEA targets (f4860cd)
- Integrate target installer Gradle artifact (88b7cd0)
- Include PDE MCP tools in help (c557da9)
- Document PDE MCP tools in CLI (dff7c44)
- Remove pde compile execute option (2f73a17)
- Improve copy-path feedback (892c591)
- Add copy-path option for target install (7f700d8)
- Bump org.eclipse.jdt:org.eclipse.jdt.core from 3.38.0 to 3.45.0 (#78) (06186cf)
- Remove workspace compile staleness warning (dfcd1d4)
- Add quiet PDE MCP launcher (f3b42c6)
- Use stdio transport for PDE MCP (0d86eaa)
- Add PDE workflow MCP tools (e727508)

### Performance
- **build**: replace p2.director with a pinned bundle-list fast path (887820f)
- **api**: reduce baseline closure before PDE API Tools (575305f)

### Refactoring
- split `pde ide-init vscode` into workspace-file generation and `pde lsp init` (b546d52)
- rename .pde to .jdtls, remove auto-setup from jdt-build (3633e8b)
- **api**: remove legacy analyzer path (c11284a)

### Tests
- filter benign Eclipse shutdown noise in workspace test (c3e9b57)
- add integration tests for workspace setup and API analysis pipeline (44755e6)
- **api**: assert analyzer runtime layout (2a11366)
- **api**: cover api analyze negative inputs (55542e2)
- **api**: cover direct analyzer cli edge cases (c317c25)
- **api**: build direct analyzer input (2425d3b)
- **api**: cover analyzer runner failure (839a413)
- **api**: add direct analyzer launch plan (1f76e45)
- **api**: add analyzer CLI runner boundary (ddd1501)
- **resolver**: make path assertions OS-agnostic (4669035)

## v0.0.8 - 2026-07-01

### Bug Fixes
- discover test config with option-only args (
c5cf5b)

### Build System
- include cli-core only via cliCorePath (
0a1774)

### Continuous Integration
- add release workflow (
852327)
- resolve cli-core via GitHub Packages (
5246b8)
- run linux only (
c2b5e8)

### Other
- remove openspec artifacts (1fab5d7)
- Update target installer README (#133) (
b7cad6)
- Fix CI checks for knime (#66) (
aa3a59)
- Document JaCoCo coverage workflow (#110) (#129) (
61e33b)
- Use cli-core color policy for maturity tags (#130) (
53a7f0)
- Deduplicate pde CLI helpers (#110) (
437178)
- Clean up pde help output (#124) (#126) (
94b852)
- Package target installer launcher (#119) (
d4159c)
- Share target configure-phase parsing (#122) (
1b154f)
- Model target install inputs (#120) (
6678cc)
- Document target installer boundaries (#101) (
231581)
- Add validate-config command (#115) (
1dabd1)
- Warn on duplicate YAML keys (#112) (
f124de)
- Handle CRaC checkpoint exit code (#109) (
b1dfb6)
- Use envFile JAVA_HOME for launches (
80840e)
- Clarify PDE config-driven MCP tools (
33b893)
- Add target pinned versions support (
6a9d8c)
- Fix IDEA module roots (
535d9d)
- Use issue IDEA project root (
f07f3c)
- Improve IDEA project initialization (
f1357c)
- Log active target extra bundles (
b440d5)
- Add target extraBundles resolution (
ddf9d8)
- Validate launch env variable names (
51ccd5)
- Use p2 profile directories for IDEA targets (
f4860c)
- Integrate target installer Gradle artifact (
88b7cd)
- Include PDE MCP tools in help (
c557da)
- Document PDE MCP tools in CLI (
dff7c4)
- Remove pde compile execute option (
2f73a1)
- Improve copy-path feedback (
892c59)
- Add copy-path option for target install (
7f700d)
- Bump org.eclipse.jdt:org.eclipse.jdt.core from 3.38.0 to 3.45.0 (#78) (
06186c)
- Remove workspace compile staleness warning (
dfcd1d)
- Add quiet PDE MCP launcher (
f3b42c)
- Use stdio transport for PDE MCP (
0d86ea)
- Add PDE workflow MCP tools (
e72750)

_Unclassified commits_:
- remove openspec artifacts
- Update target installer README (#133)
- Fix CI checks for knime (#66)
- Document JaCoCo coverage workflow (#110) (#129)
- Use cli-core color policy for maturity tags (#130)
- Deduplicate pde CLI helpers (#110)
- Clean up pde help output (#124) (#126)
- Package target installer launcher (#119)
- Share target configure-phase parsing (#122)
- Model target install inputs (#120)
- Document target installer boundaries (#101)
- Add validate-config command (#115)
- Warn on duplicate YAML keys (#112)
- Handle CRaC checkpoint exit code (#109)
- Use envFile JAVA_HOME for launches
- Clarify PDE config-driven MCP tools
- Add target pinned versions support
- Fix IDEA module roots
- Use issue IDEA project root
- Improve IDEA project initialization
- Log active target extra bundles
- Add target extraBundles resolution
- Validate launch env variable names
- Use p2 profile directories for IDEA targets
- Integrate target installer Gradle artifact
- Include PDE MCP tools in help
- Document PDE MCP tools in CLI
- Remove pde compile execute option
- Improve copy-path feedback
- Add copy-path option for target install
- Bump org.eclipse.jdt:org.eclipse.jdt.core from 3.38.0 to 3.45.0 (#78)
- Remove workspace compile staleness warning
- Add quiet PDE MCP launcher
- Use stdio transport for PDE MCP
- Add PDE workflow MCP tools

This file is automatically updated by the release tooling. Run
`./gradlew updateChangelog` after preparing a release to prepend the latest entry.
