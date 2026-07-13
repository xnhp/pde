import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import release.computeReleaseInfo
import release.reportReleaseInfo
import release.updateChangelogWith
import release.writeReleaseArtifacts

plugins {
  alias(libs.plugins.kotlin) apply false
}

allprojects {
  group = providers.gradleProperty("pluginGroup").orNull ?: "cn.varsa"
  version = providers.gradleProperty("pluginVersion").orNull ?: "0.0.0"
}

val githubPackagesUsername = providers.gradleProperty("gpr.user")
  .orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubPackagesPassword = providers.gradleProperty("gpr.key")
  .orElse(providers.environmentVariable("GITHUB_TOKEN"))

subprojects {
  repositories {
    mavenLocal()
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/xnhp/cli-core")
      credentials {
        username = githubPackagesUsername.orNull
        password = githubPackagesPassword.orNull
      }
      content {
        includeGroup("cn.varsa")
      }
    }
    mavenCentral()
  }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension>("kotlin") {
      jvmToolchain(21)
    }
  }
}

tasks.wrapper {
  gradleVersion = providers.gradleProperty("gradleVersion").get()
}

tasks.register("buildTargetInstallerLauncher") {
  description = "Build the standalone target-installer launcher jar"
  group = "build"
  dependsOn(":target-installer:targetInstallerLauncherJar")
}

// Shared by :api-analyzer and :target-installer: both need p2 metadata for the
// local Eclipse SDK to let `p2.director` resolve runtime IUs offline, rather than
// falling back to a remote p2 site on every build. Registered once at the root so
// both consumers -- and, via the local build cache, repeat invocations -- reuse a
// single publish of the (unchanging) local SDK instead of redoing it per-tool.
val eclipseSdkDir = providers.gradleProperty("eclipseSdk").map { file(it) }
val sdkP2RepoDir = layout.buildDirectory.dir("sdk-p2repo")

val publishSdkP2Repo by tasks.registering(Exec::class) {
  description = "Publish the local Eclipse SDK as a p2 repository so runtime IUs resolve offline"
  group = "build"
  notCompatibleWithConfigurationCache("Computes commandLine from the local Eclipse SDK layout at execution time")
  onlyIf { eclipseSdkDir.orNull?.let { it.resolve("plugins").isDirectory } == true }

  // Only plugins/features affect published p2 metadata; the rest of a live Eclipse
  // SDK install (configuration/, workspace/) mutates constantly just from being run
  // and would otherwise make this task look "changed" on every build.
  inputs.dir(eclipseSdkDir.map { it.resolve("plugins") })
  inputs.dir(eclipseSdkDir.map { it.resolve("features") })
  outputs.dir(sdkP2RepoDir)
  outputs.cacheIf { true }

  doFirst {
    val sdk = eclipseSdkDir.get()
    val launcherJar = sdk.resolve("plugins")
      .listFiles { f -> f.name.startsWith("org.eclipse.equinox.launcher_") && f.name.endsWith(".jar") }
      ?.firstOrNull()
      ?: throw GradleException("Unable to locate org.eclipse.equinox.launcher in $sdk")
    val repoDir = sdkP2RepoDir.get().asFile
    repoDir.deleteRecursively()
    repoDir.mkdirs()
    commandLine(
      "java", "-jar", launcherJar.absolutePath,
      "-application", "org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher",
      "-metadataRepository", "file:$repoDir",
      "-artifactRepository", "file:$repoDir",
      "-source", sdk.absolutePath,
      "-compress", "-publishArtifacts"
    )
  }
}

val generateReleaseInfo by tasks.registering {
  notCompatibleWithConfigurationCache("Uses git CLI to compute release metadata")
  outputs.file(layout.buildDirectory.file("release/version.txt"))
  outputs.file(layout.buildDirectory.file("release/notes.md"))
  doLast {
    val versionOverride = providers.gradleProperty("releaseVersion").orNull?.takeIf { it.isNotBlank() }
    val info = computeReleaseInfo(versionOverride)
    writeReleaseArtifacts(info)
    logger.reportReleaseInfo(info)
  }
}

tasks.register("updateChangelog") {
  notCompatibleWithConfigurationCache("Writes CHANGELOG.md using git outputs")
  dependsOn(generateReleaseInfo)
  inputs.file(layout.buildDirectory.file("release/notes.md"))
  outputs.file(layout.projectDirectory.file("CHANGELOG.md"))
  doLast {
    val notesFile = layout.buildDirectory.file("release/notes.md").get().asFile
    updateChangelogWith(notesFile.readText())
  }
}
