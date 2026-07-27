# Changelog

## v0.1.0 - 2026-07-27

### Bug Fixes
- also skip api-baseline/component-resolution/fatal categories in add-all-from-report (
909bf1)
- api-baseline reports written without .json extension miss add-all-from-report discovery (
c5779d)
- align .api_filters matching with Eclipse PDE semantics (
d9b474)
- suppress false-positive system.bundle Require-Bundle warning in API baseline diagnostics (
1cf00c)
- vscode code-workspace uses bundle paths instead of VCS roots (
a0cdc1)
- vscode code-workspace uses bundle paths instead of VCS roots (
96eb3f)
- handle CompositeApiDescription wrapper in refreshPackages reflection (
c794eb)
- list `pde target install api-baseline` in the --help command tree (
36d6a2)
- workspace setup's target classpath now expands Bundle-ClassPath entries (
7441af)
- align pde ide-init vscode with jdt-workspace/api-baseline rename, fix stale test assertion (
617079)
- clarify help text for pde schema subcommand (
43c09c)
- discoverConfigFile walks parent directories for auto-discovery (
300d6c)
- disable auto-build in workspace setup to prevent JDT NPE (
5fba12)
- enable since-tag detection for workspace ProjectComponents (
fd4cbe)
- restore ProjectComponent with detached-state description override (
db9920)
- remove ProjectComponent usage to avoid OSGi state ownership conflict (
39c648)
- match pde.api.tools compile dependency version to runtime (1.3.600) (
946516)
- point api-analyze Equinox -data to workspace setup directory when --workspace-data is provided (
7a4da6)
- link META-INF at project root and add project classpath entries for workspace deps (
810133)
- exclude bundle root from classpath entries to avoid JDT nesting error (
29980c)
- correct application IDs and add required runtime bundles (
4402b5)
- make workspaceSetupMain, EquinoxAppInvocation, EquinoxAppRuntime public (
ebaed3)
- remove leaked test artifacts and embedded repos (
db1de4)
- **lsp**: use cli-core logging in lsp init (
e6f3cf)
- **jdtls**: skip non-Java bundles from .project/.classpath generation (
afe978)
- **jdtls**: rename generated .jdtls-data folder to .lsp (
3670c9)
- **api**: walk Import-Package/Require-Capability edges in target dependency closure (
38266b)
- **api**: drop conflicting singleton dependency-scope artifact from baseline/current merge (
53b935)
- **api**: pull Fragment-Host closure into api-analyze dependency set (
9179db)
- **api**: compute Require-Bundle diagnostics across current+dependency scope (
2b8a1e)
- **api**: include every target version needed by Require-Bundle ranges (
d00b28)
- **api**: update stale --log help text and log the batch launch (
fb2a0b)
- **api**: drop the legacy single-bundle analyzer input format (
1550f3)
- **api**: actually batch pde api-analyze into one analyzer JVM (
405c97)
- **api**: harden direct analyzer launch (
7233c2)
- **api**: clean analyzer shutdown (
d7279b)
- **target**: rebuild index from artifact metadata (
4bd884)
- **intellij**: run library root updates on EDT (
133ceb)
- **target**: use uppercase profile registry path (
9f5b61)
- **target**: fail when installer plugin is missing (
68a725)
- **target**: fail fast when Eclipse SDK is missing (
da3c49)
- preserve launch args as atomic (
043f4e)
- discover test config with option-only args (
c5cf5b)

### Build System
- **api**: replace api-analyzer/target-installer Exec scripts with Gradle-native tasks (
61b451)
- **target**: use javac argfile for installer compilation (
8a4af1)
- **target**: build target-installer for Java 21 (
72f21f)
- include cli-core only via cliCorePath (
0a1774)

### Chores
- remove pde add-test-helper CLI subcommand (
ca745e)
- gitignore stale Claude worktrees (
593680)
- automate release versioning (
a48b1c)

### Continuous Integration
- lint pull request head commits (
e4c1c3)
- add release workflow (
852327)
- resolve cli-core via GitHub Packages (
5246b8)
- run linux only (
c2b5e8)

### Documentation
- document that VS Code JDT LS and pde jdt-workspace build share output but not build state (
24b74f)

### Features
- expose api-baseline filters prune/regroup in the picocli command tree (22401b9)
- add `pde api-baseline filters prune`, regroup filter commands under `filters` (
978244)
- default 'pde jdt-workspace build' --data to init's output path (
0d2940)
- add pde lsp ignore/unignore to skip-worktree .project/.classpath/.prefs (
67ac7a)
- add --allow-missing-fields to api-baseline add-all-from-report (
ab4a6b)
- actually apply .api_filters suppression in direct API analyzer (
0f1ad2)
- warn in ide-init when a bundle's fetch_jars lib folder has no jars (
494dc7)
- add pde init-config command; fix modules.xml template (
b40d2d)
- remove non-functional staleness check from pde compile (
4ae2e8)
- move api-filters to api-baseline, add add-filter command (
14a571)
- generate VS Code tasks.json/launch.json for launches/tests (
d79272)
- generate VS Code tasks.json/launch.json for launches/tests (
432572)
- render pde --help command tree with unicode box-drawing guides (
4f5954)
- switch WorkspaceSetupService to visible-mode, fold JDT LS project files into pde ide-init vscode (
2c568a)
- rework api-analyze CLI surface as api-baseline (
0065b3)
- render full nested command tree for top-level pde --help (
b2c3bc)
- change default target output directory from target/ to .target/ (
cae13d)
- add CLI commands for workspace-setup, jdt-build, and api-analyze workspace support (
e939d6)
- bundle Eclipse JDT formatter JARs with pde-format, removing runtime Eclipse dependency (
90c7c3)
- add JDT build Equinox application (
48fe84)
- add ProjectComponent support to api-analyze for since-tag checks (
84b22b)
- add workspace-setup Equinox application (
72c3ee)
- add WorkspaceProjectSpec and WorkspaceSetupInput JSON contracts (
383e12)
- **jdtls**: write .project/.classpath into .jdtls-data/projects/ instead of bundle directories (
d9c23f)
- **lsp**: consolidate JDT LS init/run under `pde lsp`, add `pde ide-init vscode` (
cc6873)
- **api**: launch packaged analyzer runtime (
d3f789)
- **api**: default to direct analyzer app (
80f84d)
- **api**: route direct analyzer through input manifests (
52c7da)
- **api**: add direct API analyzer runtime (
b63e9b)
- **target**: add bundle pool health repair commands (
19b190)
- **config**: gate bundle whitelist behavior (
7af06e)
- **target**: trust all p2 authorities on opt-in (
e960b5)

### Other
- Merge branch 'vscode-launch-debug' into knime (
452396)
- compat with current IJ version (
a7900e)
- no recover workspace (
0a1b23)
- Merge branch 'feat/pde-help-tree' into integration/pde-todos (
5606b5)
- Merge branch 'feat/dot-target-layout' into integration/pde-todos (
76f9f1)
- Merge branch 'feat/api-baseline-rework' into integration/pde-todos (
612cca)
- Merge branch 'feat/jdt-workspace-rename' into integration/pde-todos (
2541f0)
- Merge branch 'chore/remove-add-test-helper' into integration/pde-todos (
c308c9)
- Rename pde workspace/jdt-build commands to jdt-workspace group (
f50051)
- test improvements (
e1df93)
- Merge branch 'cli-integration' into knime (
6a93b2)
- Merge branch 'fix/api-analyze-singleton-scope-collision' (
30b3ae)
- Merge branch 'fix/api-analyze-fragment-host-closure' (
12408d)
- API analysis batch workspace bundles in one analyzer JVM (
913136)
- Update skill with info on api analysis (
76327e)
- Install org.eclipse.osgi.services only when present (
be0d7a)
- remove openspec artifacts (
1fab5d)
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

### Performance
- **build**: replace p2.director with a pinned bundle-list fast path (
887820)
- **api**: reduce baseline closure before PDE API Tools (
575305)

### Refactoring
- split `pde ide-init vscode` into workspace-file generation and `pde lsp init` (
b546d5)
- rename .pde to .jdtls, remove auto-setup from jdt-build (
3633e8)
- **api**: remove legacy analyzer path (
c11284)

### Tests
- filter benign Eclipse shutdown noise in workspace test (
c3e9b5)
- add integration tests for workspace setup and API analysis pipeline (
44755e)
- **api**: assert analyzer runtime layout (
2a1136)
- **api**: cover api analyze negative inputs (
55542e)
- **api**: cover direct analyzer cli edge cases (
c317c2)
- **api**: build direct analyzer input (
2425d3)
- **api**: cover analyzer runner failure (
839a41)
- **api**: add direct analyzer launch plan (
1f76e4)
- **api**: add analyzer CLI runner boundary (
ddd150)
- **resolver**: make path assertions OS-agnostic (
466903)

_Unclassified commits_:
- Merge branch 'vscode-launch-debug' into knime
- compat with current IJ version
- no recover workspace
- Merge branch 'feat/pde-help-tree' into integration/pde-todos
- Merge branch 'feat/dot-target-layout' into integration/pde-todos
- Merge branch 'feat/api-baseline-rework' into integration/pde-todos
- Merge branch 'feat/jdt-workspace-rename' into integration/pde-todos
- Merge branch 'chore/remove-add-test-helper' into integration/pde-todos
- Rename pde workspace/jdt-build commands to jdt-workspace group
- test improvements
- Merge branch 'cli-integration' into knime
- Merge branch 'fix/api-analyze-singleton-scope-collision'
- Merge branch 'fix/api-analyze-fragment-host-closure'
- API analysis batch workspace bundles in one analyzer JVM
- Update skill with info on api analysis
- Install org.eclipse.osgi.services only when present
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

## Unreleased

### Breaking Changes
- Default generated/output directory changed from `target/` to `.target/` (e.g. `target/p2`,
  `target/install`, `target/bundle-pool` -> `.target/p2`, `.target/install`, `.target/bundle-pool`).
  Projects relying on the previous default should either set `target.p2Path`, `target.install`,
  and `target.bundlePool` explicitly in `pde.yaml` to keep using an existing `target/` directory,
  or rename/regenerate the directory as `.target/`.

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
