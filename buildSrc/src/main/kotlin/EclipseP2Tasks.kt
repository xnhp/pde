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
 * Resolves the Equinox launcher jar inside an Eclipse SDK install, used to invoke
 * headless p2 applications (FeaturesAndBundlesPublisher, p2.director).
 */
fun Project.equinoxLauncherJar(sdkDir: Provider<File>): Provider<File> = sdkDir.map { sdk ->
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
 * branch switches, and `clean` -- see docs/pinned-runtime-bundles.md for why this exists.
 */
fun Project.pinnedRuntimeBundleCacheDir(): Provider<Directory> =
  layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/pde-pinned-runtime-bundles") })

/**
 * Materializes an Equinox runtime by copying a pre-resolved, checked-in set of bundle jars
 * (named in [lockFile], one filename per line) out of the shared [cacheDir], instead of running
 * p2.director. This is the FAST default path used by `assemble`/`installDist` -- see
 * docs/pinned-runtime-bundles.md for the full rationale and the regeneration procedure.
 */
fun Project.registerPinnedRuntimeMaterialize(
  taskName: String,
  lockFile: Provider<RegularFile>,
  cacheDir: Provider<Directory>,
  appBundlePluginsDir: Provider<Directory>,
  launcherJar: Provider<File>,
  destinationDir: Provider<Directory>
): TaskProvider<Task> = tasks.register(taskName) {
  description = "Materialize an Equinox runtime from the pinned bundle set (fast path, see docs/pinned-runtime-bundles.md)"
  group = "build"

  inputs.file(lockFile)
  inputs.dir(appBundlePluginsDir)
  inputs.file(launcherJar)
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
            "(needs the local Eclipse SDK / p2Repositories fallback) to (re)populate it, then retry.\n" +
            "See docs/pinned-runtime-bundles.md."
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
 * Manual/CI-only: NOT wired into `assemble`/`build`/`installDist`. Run this only when the local
 * Eclipse SDK version changes (expected to be rare) -- see docs/pinned-runtime-bundles.md.
 */
fun Project.registerRegeneratePinnedRuntimeBundles(
  taskName: String,
  p2ResolvedRuntimeDir: Provider<Directory>,
  lockFile: Provider<RegularFile>,
  cacheDir: Provider<Directory>,
  excludeNamePrefixes: Provider<List<String>>
): TaskProvider<Task> = tasks.register(taskName) {
  description = "Regenerate the pinned runtime bundle set from a real p2.director resolution (manual/CI only, see docs/pinned-runtime-bundles.md)"
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
