package eclipsep2

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
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
