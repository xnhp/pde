# Changelog

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
