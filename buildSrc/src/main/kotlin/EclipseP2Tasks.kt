package eclipsep2

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * True when the build was asked to skip everything that needs a real Eclipse SDK install
 * (`-PskipEclipseRuntimes=true`).
 *
 * Materializing an Equinox runtime needs either a local Eclipse SDK (`eclipseSdk`, which defaults
 * to a developer-machine path) or a warm pinned-bundle cache in the Gradle user home (see
 * [pinnedRuntimeBundleCacheDir]). Neither exists on a plain CI runner, yet the resulting runtime
 * archives are wired into `processResources`/`assemble`, which drags `check` into needing an SDK
 * just to run unit tests that never touch a runtime.
 *
 * This flag unhooks the runtime archives from `processResources`/`assemble` so `check` is
 * SDK-free. It deliberately does NOT make the materialize tasks silently succeed: anything that
 * actually ships a runtime (`distZip`, `buildPlugin`, the Release workflow) must run without this
 * flag, and still fails loudly if the SDK is missing, rather than publishing a gutted artifact.
 */
fun Project.skipEclipseRuntimes(): Boolean =
  providers.gradleProperty("skipEclipseRuntimes").orNull?.toBoolean() == true

/**
 * Resolves the Equinox launcher jar inside an Eclipse SDK install, used to invoke
 * headless p2 applications (FeaturesAndBundlesPublisher, p2.director).
 */
fun Project.equinoxLauncherJar(sdkDir: Provider<File>): Provider<File> = provider {
  providers.gradleProperty("eclipseLauncherJar").orNull?.let { jarPath ->
    val jar = file(jarPath)
    if (jar.isFile) return@provider jar
  }
  val sdk = sdkDir.get()
  sdk.resolve("plugins")
    .listFiles { f -> f.name.startsWith("org.eclipse.equinox.launcher_") && f.name.endsWith(".jar") }
    ?.firstOrNull()
    ?: throw GradleException("Unable to locate org.eclipse.equinox.launcher in $sdk")
}

/**
 * Publishes a directory of OSGi bundle(s) as a local p2 repository via
 * FeaturesAndBundlesPublisher, so `p2.director` can resolve them without a live
 * network repository. Shared shape for both :api-analyzer and :target-installer's
 * "publish the app bundle" step -- kept as Exec since there is no native Gradle p2
 * task type, but with real inputs/outputs so it participates in up-to-date checks
 * and the build cache like any other task.
 */
fun Project.registerPublishAppP2Repo(
  taskName: String,
  launcherJar: Provider<File>,
  sourceDir: Provider<Directory>,
  outputDir: Provider<Directory>
): TaskProvider<Exec> = tasks.register(taskName, Exec::class.java) {
  description = "Publish the app bundle as a p2 repository"
  group = "build"
  notCompatibleWithConfigurationCache("Computes commandLine from the app bundle directory at execution time")

  inputs.dir(sourceDir)
  outputs.dir(outputDir)
  outputs.cacheIf { true }

  doFirst {
    val dir = outputDir.get().asFile
    dir.deleteRecursively()
    dir.mkdirs()
    commandLine(
      "java", "-jar", launcherJar.get().absolutePath,
      "-application", "org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher",
      "-metadataRepository", "file:$dir",
      "-artifactRepository", "file:$dir",
      "-source", sourceDir.get().asFile.absolutePath,
      "-compress", "-publishArtifacts"
    )
  }
}

/**
 * Materializes an Equinox runtime by running `p2.director` against one or more
 * local p2 repositories (plus an optional remote fallback), installing a fixed set
 * of IUs into [destinationDir]. Shared shape for both tools' "materialize runtime"
 * step. Kept as Exec (no native Gradle p2 task type exists) but with real
 * inputs/outputs so unrelated changes don't force a re-resolve.
 *
 * This is the slow, real resolution -- not wired into `assemble`/`installDist` by default (see
 * [registerPinnedRuntimeMaterialize], the fast path that replaces it there). Each of the four
 * tools (`api-analyzer`, `target-installer`, `workspace-setup`, `jdt-build`) embeds the whole
 * `pde-resolver` module in its OSGi app bundle jar, so any `pde-resolver` change invalidates all
 * four `p2.director` resolutions at once -- and `p2.director` is an unconstrained-heap JVM doing
 * real OSGi dependency-graph solving (~20-30s solo; measured one run go from 24s solo to 2m30s
 * under 3-way concurrency), so running it on every build made `pde-resolver` iteration painfully
 * slow. `p2.director`'s only real job is resolving a fixed IU set into its transitive jar closure
 * and copying those jars into a directory -- nothing about its own provisioning model (profiles,
 * `.p2` metadata) survives past that copy, since `bundles.info`/`config.ini` get regenerated from
 * each jar's `MANIFEST.MF` at runtime anyway (`ensureEquinoxAppRuntimeConfiguration` in
 * `core/pde-launch-engine/.../cli/Main.kt`). That result barely changes (only on an SDK bump or an
 * `installIUs` change), so [registerRegeneratePinnedRuntimeBundles] snapshots it into a lockfile
 * once instead of recomputing it every time.
 */
fun Project.registerMaterializeRuntime(
  taskName: String,
  launcherJar: Provider<File>,
  repositoryDirs: Provider<List<File>>,
  extraRepositories: Provider<String>,
  installIUs: Provider<List<String>>,
  destinationDir: Provider<Directory>
): TaskProvider<Exec> = tasks.register(taskName, Exec::class.java) {
  description = "Materialize an Equinox runtime via p2.director"
  group = "build"
  notCompatibleWithConfigurationCache("Computes commandLine from resolved p2 repositories at execution time")

  inputs.files(repositoryDirs)
  inputs.property("installIUs", installIUs)
  inputs.property("extraRepositories", extraRepositories.orElse(""))
  outputs.dir(destinationDir)
  outputs.cacheIf { true }

  doFirst {
    val dest = destinationDir.get().asFile
    dest.deleteRecursively()
    dest.mkdirs()
    val repos = (
      repositoryDirs.get().map { "file:$it" } +
        listOfNotNull(extraRepositories.orNull?.takeIf { it.isNotBlank() })
      ).joinToString(",")
    commandLine(
      "java", "-jar", launcherJar.get().absolutePath,
      "-application", "org.eclipse.equinox.p2.director",
      "-repository", repos,
      "-installIU", installIUs.get().joinToString(","),
      "-destination", dest.absolutePath,
      "-profile", "DefaultProfile",
      "-bundlepool", dest.absolutePath
    )
  }
}

/**
 * Shared, per-machine cache of pinned runtime bundle jars, keyed by filename. Lives under the
 * Gradle user home (not inside any single worktree/checkout) so it survives `git worktree add`,
 * branch switches, and `clean`.
 *
 * Only the filename list (`tools/<tool>/runtime-bundles.lock`, one name per line) is checked into
 * git -- the resolved sets are 80-100+ Eclipse SDK jars (tens of MB), and vendoring that much
 * binary content would bloat clones/diffs for no benefit. The lockfile is a trivial, reviewable
 * diff and is fully reproducible from the cache at any time by rerunning
 * `regeneratePinnedRuntimeBundles` (re-resolves via `p2.director`, repopulates the cache, rewrites
 * the lockfile). If a runtime failure looks like a missing bundle (`NoClassDefFoundError`,
 * `ClassNotFoundException`, a missing extension point) even without an SDK version bump, that's
 * the first thing to try: `p2.director`'s dependency resolution is more semantically complete than
 * this snapshot (it also resolves service/capability wiring like SCR or the extension registry),
 * so a code path that was never exercised the last time the lockfile was generated can be missing
 * a jar that a fresh resolution would include.
 */
fun Project.pinnedRuntimeBundleCacheDir(): Provider<Directory> =
  layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/pde-pinned-runtime-bundles") })

/**
 * Resolves the compile-time jar for one internal-API bundle (e.g. `org.eclipse.pde.api.tools`),
 * preferring the *exact* jar the pinned runtime uses (looked up by [namePrefix] in [lockFile],
 * fetched from the shared [cacheDir], see [pinnedRuntimeBundleCacheDir]) so compile-time and
 * runtime can never drift onto different versions of an internal, unversioned Maven-coordinate-free
 * API surface. Falls back to whatever version is in the local Eclipse SDK's
 * `plugins/` dir when the pinned cache isn't warm yet (e.g. `ci.yml`'s `-PskipEclipseRuntimes=true`
 * fast path, which still has the SDK but skips populating the cache) -- that keeps `check` SDK-only,
 * at the cost of a possible (compile-time-only, caught by tests) version mismatch until the cache
 * is regenerated.
 */
fun Project.pinnedRuntimeBundleJar(
  lockFile: Provider<RegularFile>,
  cacheDir: Provider<Directory>,
  sdkDir: Provider<File>,
  namePrefix: String
): Provider<RegularFile> = layout.file(provider {
  val lock = lockFile.get().asFile
  val pinnedName = lock.takeIf { it.isFile }
    ?.readLines()
    ?.map { it.trim() }
    ?.firstOrNull { it.startsWith(namePrefix) }
  if (pinnedName != null) {
    val cached = cacheDir.get().asFile.resolve(pinnedName)
    if (cached.isFile) return@provider cached
  }

  val sdkJar = sdkDir.orNull?.resolve("plugins")
    ?.listFiles { f -> f.name.startsWith(namePrefix) && f.name.endsWith(".jar") }
    ?.firstOrNull()

  sdkJar ?: throw GradleException(
    "Unable to locate a compile-time jar for '$namePrefix'.\n" +
      "Checked the pinned runtime bundle cache (${cacheDir.get().asFile}, keyed by $lock) " +
      "and the Eclipse SDK at ${sdkDir.orNull}.\n" +
      "Run './gradlew :api-analyzer:regeneratePinnedRuntimeBundles' to warm the pinned cache, " +
      "or point -PeclipseSdk at a valid Eclipse install."
  )
})

/**
 * Materializes an Equinox runtime by copying a pre-resolved, checked-in set of bundle jars
 * (named in [lockFile], one filename per line) out of the shared [cacheDir], instead of running
 * p2.director. This is the FAST default path used by `assemble`/`installDist` (see
 * [registerMaterializeRuntime] for why this exists and [registerRegeneratePinnedRuntimeBundles]
 * for how the pinned set is (re)computed).
 */
fun Project.registerPinnedRuntimeMaterialize(
  taskName: String,
  lockFile: Provider<RegularFile>,
  cacheDir: Provider<Directory>,
  appBundlePluginsDir: Provider<Directory>,
  launcherJar: Provider<File>,
  destinationDir: Provider<Directory>
): TaskProvider<Task> = tasks.register(taskName) {
  description = "Materialize an Equinox runtime from the pinned bundle set (fast path)"
  group = "build"

  inputs.file(lockFile)
  inputs.dir(appBundlePluginsDir)
  // cacheDir is deliberately NOT declared as a task input. It lives outside the project (shared
  // Gradle user home cache, see pinnedRuntimeBundleCacheDir), so its mtime/content churns for
  // reasons unrelated to this build (other worktrees regenerating it, cache eviction, etc.) --
  // the lockFile listing is the actual dependency-relevant input.
  outputs.dir(destinationDir)
  outputs.cacheIf { true }

  doLast {
    val dest = destinationDir.get().asFile
    dest.deleteRecursively()
    val pluginsDir = dest.resolve("plugins")
    pluginsDir.mkdirs()

    val cache = cacheDir.get().asFile
    val names = lockFile.get().asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    names.forEach { name ->
      val source = cache.resolve(name)
      if (!source.exists()) {
        throw GradleException(
          "Pinned runtime bundle '$name' is missing from the shared cache at $cache.\n" +
            "Run './gradlew ${path.removeSuffix(":$taskName")}:regeneratePinnedRuntimeBundles' once " +
            "(needs the local Eclipse SDK / p2Repositories fallback) to (re)populate it, then retry."
        )
      }
      source.copyRecursively(pluginsDir.resolve(name), overwrite = true)
    }

    appBundlePluginsDir.get().asFile.listFiles()?.forEach { f ->
      f.copyRecursively(pluginsDir.resolve(f.name), overwrite = true)
    }

    val launcher = launcherJar.get()
    launcher.copyTo(pluginsDir.resolve(launcher.name), overwrite = true)
  }
}

/**
 * Regenerates the pinned bundle set for one tool: takes the plugins/ directory produced by a
 * REAL p2.director resolution ([p2ResolvedRuntimeDir]), copies everything except the app's own
 * bundle jar and the equinox launcher (both always freshly built/copied, never pinned) into the
 * shared [cacheDir], and rewrites [lockFile] with the resulting sorted filename list.
 *
 * Manual/CI-only: NOT wired into `assemble`/`build`/`installDist`. Needs the local Eclipse SDK
 * (`eclipseSdk` in `gradle.properties`) and, currently, the `p2Repositories` remote fallback
 * (still load-bearing for the `osgi.ee JavaSE-21` capability gap documented there). Commit the
 * resulting `tools/<tool>/runtime-bundles.lock` change. Run this (once per tool: `api-analyzer`,
 * `target-installer`, `workspace-setup`, `jdt-build`) when:
 * - the local Eclipse SDK install is upgraded to a new version;
 * - a tool's `installIUs` list changes (a bundle dependency is added/removed); or
 * - [registerPinnedRuntimeMaterialize] fails with "missing from the shared cache" (a fresh
 *   machine, or a cleared cache, doesn't have the jars the lockfile names yet).
 *
 * Expected to be rare (tied to SDK upgrades) -- if this needs running on every build, something
 * has gone wrong with the pinning and is worth investigating rather than working around.
 */
fun Project.registerRegeneratePinnedRuntimeBundles(
  taskName: String,
  p2ResolvedRuntimeDir: Provider<Directory>,
  lockFile: Provider<RegularFile>,
  cacheDir: Provider<Directory>,
  excludeNamePrefixes: Provider<List<String>>
): TaskProvider<Task> = tasks.register(taskName) {
  description = "Regenerate the pinned runtime bundle set from a real p2.director resolution (manual/CI only)"
  group = "build"
  outputs.upToDateWhen { false }

  doLast {
    val resolvedPlugins = p2ResolvedRuntimeDir.get().asFile.resolve("plugins")
    val cache = cacheDir.get().asFile
    cache.mkdirs()
    val excludes = excludeNamePrefixes.get()

    val pinned = resolvedPlugins.listFiles()
      ?.filterNot { f -> excludes.any { prefix -> f.name.startsWith(prefix) } }
      ?.sortedBy { it.name }
      ?: emptyList()

    pinned.forEach { f -> f.copyRecursively(cache.resolve(f.name), overwrite = true) }

    val lock = lockFile.get().asFile
    lock.parentFile.mkdirs()
    lock.writeText(pinned.joinToString(System.lineSeparator()) { it.name } + System.lineSeparator())

    logger.lifecycle("Pinned ${pinned.size} runtime bundles into $cache; lockfile written to $lock")
  }
}
