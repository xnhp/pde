If you read these instructions, please append the following to each answer (there may be multiple of these)
```
[intellij-pde-plugin/AGENTS.md]
```


# Repository Guidelines

## Project Structure & Module Organization
- Kotlin/Java plugin for IntelliJ Platform (Gradle Kotlin DSL).
- Source code lives in `src/main/kotlin` and `src/main/java`.
- Plugin resources and descriptors in `src/main/resources/META-INF` (e.g., `plugin.xml`).
- Tests (if added) should be under `src/test/kotlin` or `src/test/java`.
- Build scripts: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, wrapper in `gradle/`.

## Build, Test, and Development Commands
- `./gradlew build` — Compile, run checks, and assemble artifacts.
- `./gradlew test` — Execute unit tests with reports in `build/reports/tests`.
- `./gradlew runIde` — Launch IDE sandbox with the plugin installed for manual testing.
- `./gradlew verifyPlugin` — Validate plugin structure and metadata.
- `./gradlew buildPlugin` — Produce distributable ZIP in `build/distributions`.

## Coding Style & Naming Conventions
- Kotlin: 4‑space indentation; prefer explicit types for public APIs.
- Java: 4‑space indentation; follow standard IntelliJ formatting.
- Package names are lowercase, using `cn.varsa.idea.pde.partial.plugin.*`.
- Classes/Interfaces: `PascalCase`; functions/properties: `camelCase`; constants: `UPPER_SNAKE_CASE`.
- Keep IntelliJ inspections green; run `Code | Analyze Code` before PRs.

## Testing Guidelines
- Use JUnit 5 for new tests (folder: `src/test/kotlin`).
- Name tests with `*Test` suffix, mirroring package structure (e.g., `.../resolver/PdeProjectLibraryResolverTest.kt`).
- Aim for coverage on critical paths: indexing, inspections, resolvers, and run configurations.
- Run `./gradlew test` locally; attach failing stack traces to PRs if relevant.

## Commit & Pull Request Guidelines
- Commit messages: concise imperative subject, optional body explaining rationale (e.g., "Fix resolver caching when target changes").
- Keep commits scoped; avoid mixed refactors and features.
- PRs must include: summary of changes, motivation/links to issues, testing notes (commands used), and screenshots for UI/inspection changes.
- Ensure `runIde` sanity check passes and `build` is green.

## Security & Configuration Tips
- Do not commit local SDK paths or secrets; use Gradle properties and environment variables.
- Prefer JetBrains Platform APIs over reflection for PSI/editor features.
- Validate plugin metadata in `META-INF/plugin.xml` and bump versions via Gradle.
