import eclipsep2.equinoxLauncherJar
import eclipsep2.registerPublishAppP2Repo
import eclipsep2.registerMaterializeRuntime
import eclipsep2.registerPinnedRuntimeMaterialize
import eclipsep2.registerRegeneratePinnedRuntimeBundles
import eclipsep2.pinnedRuntimeBundleCacheDir
import eclipsep2.skipEclipseRuntimes

plugins {
  base
}

repositories {
  mavenCentral()
}

val appLibs by configurations.creating

dependencies {
  appLibs(project(":pde-resolver")) {
    isTransitive = false
  }
  appLibs("org.jetbrains.kotlin:kotlin-stdlib")
  appLibs("com.fasterxml.jackson.core:jackson-annotations:2.17.1")
  appLibs("com.fasterxml.jackson.core:jackson-core:2.17.1")
  appLibs("com.fasterxml.jackson.core:jackson-databind:2.17.1")
  appLibs("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.17.1")
  appLibs("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1")
  appLibs("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
}

val appBsn = "cn.varsa.pde.jdt_build"
val appVersion = "1.0.0"

val eclipseSdkDir = providers.gradleProperty("eclipseSdk").map { file(it) }
val launcherJar = equinoxLauncherJar(eclipseSdkDir)

val appBundleDir = layout.buildDirectory.dir("appBundle")

val appBundleJar by tasks.registering(Jar::class) {
  description = "Package the JDT builder as an OSGi bundle"
  group = "build"
  archiveFileName = "$appBsn-$appVersion.jar"
  destinationDirectory = appBundleDir.map { it.dir("plugins") }

  manifest {
    attributes(
      "Bundle-ManifestVersion" to "2",
      "Bundle-SymbolicName" to "$appBsn;singleton:=true",
      "Bundle-Version" to appVersion,
      "Bundle-Name" to "pde JDT Builder",
      "Bundle-RequiredExecutionEnvironment" to "JavaSE-21",
      "Bundle-ClassPath" to (
        listOf(".") + appLibs.files.sortedBy { it.name }.map { "lib/${it.name}" }
        ).joinToString(","),
      "Require-Bundle" to listOf(
        "org.eclipse.equinox.app", "org.eclipse.core.runtime", "org.eclipse.core.resources",
        "org.eclipse.jdt.core"
      ).joinToString(",")
    )
  }

  from(layout.projectDirectory.file("plugin.xml"))
  into("lib") { from(appLibs) }
}

val publishAppP2Repo = registerPublishAppP2Repo(
  taskName = "publishAppP2Repo",
  launcherJar = launcherJar,
  sourceDir = appBundleDir,
  outputDir = layout.buildDirectory.dir("app-p2repo")
)
publishAppP2Repo { dependsOn(appBundleJar) }

val installIUs = provider {
  listOf(
    appBsn, "org.eclipse.equinox.launcher", "org.eclipse.equinox.simpleconfigurator",
    "org.eclipse.osgi.compatibility.state", "org.apache.felix.scr",
    "org.eclipse.jdt.core"
  )
}

val sdkP2Repo = rootProject.layout.buildDirectory.dir("sdk-p2repo")
val runtimeDir = layout.buildDirectory.dir("runtime")

// Real p2.director resolution -- manual/CI-only, see docs/pinned-runtime-bundles.md. Feeds
// regeneratePinnedRuntimeBundles below; NOT part of assemble/installDist (materializeJdtBuildRuntime is).
val p2ResolvedRuntimeDir = layout.buildDirectory.dir("p2-resolved-runtime")
val resolveRuntimeViaP2Director = registerMaterializeRuntime(
  taskName = "resolveRuntimeViaP2Director",
  launcherJar = launcherJar,
  repositoryDirs = provider { listOf(publishAppP2Repo.get().outputs.files.singleFile, sdkP2Repo.get().asFile) },
  extraRepositories = providers.gradleProperty("p2Repositories"),
  installIUs = installIUs,
  destinationDir = p2ResolvedRuntimeDir
)
resolveRuntimeViaP2Director {
  dependsOn(publishAppP2Repo, rootProject.tasks.named("publishSdkP2Repo"))
  doLast {
    val pluginsDir = p2ResolvedRuntimeDir.get().asFile.resolve("plugins")
    if (pluginsDir.listFiles { f -> f.name.startsWith("${appBsn}_") && f.name.endsWith(".jar") }.isNullOrEmpty()) {
      throw org.gradle.api.GradleException("Runtime materialization failed: $appBsn missing from $pluginsDir")
    }
  }
}

val pinnedBundleCache = pinnedRuntimeBundleCacheDir()
val lockFile = layout.projectDirectory.file("runtime-bundles.lock")

registerRegeneratePinnedRuntimeBundles(
  taskName = "regeneratePinnedRuntimeBundles",
  p2ResolvedRuntimeDir = p2ResolvedRuntimeDir,
  lockFile = provider { lockFile },
  cacheDir = pinnedBundleCache,
  // p2.director renames installed bundles to the BSN_version convention regardless of the
  // source jar's own filename (appBundleJar names it "$appBsn-$appVersion.jar", dash-separated),
  // so the exclude match must use the underscore form p2 actually produces on disk.
  excludeNamePrefixes = provider { listOf("${appBsn}_", "org.eclipse.equinox.launcher_") }
).configure { dependsOn(resolveRuntimeViaP2Director) }

val materializeJdtBuildRuntime = registerPinnedRuntimeMaterialize(
  taskName = "materializeJdtBuildRuntime",
  lockFile = provider { lockFile },
  cacheDir = pinnedBundleCache,
  appBundlePluginsDir = appBundleDir.map { it.dir("plugins") },
  launcherJar = launcherJar,
  destinationDir = runtimeDir
)
materializeJdtBuildRuntime { dependsOn(appBundleJar) }

val jdtBuildRuntimeZip by tasks.registering(Zip::class) {
  description = "Assemble the JDT build runtime zip"
  group = "build"
  dependsOn(materializeJdtBuildRuntime)
  from(runtimeDir)
  archiveFileName = "jdt-build-runtime.zip"
  destinationDirectory = layout.buildDirectory.dir("libs")
}

// -PskipEclipseRuntimes=true keeps `assemble`/`build` off the Eclipse-SDK path so a
// checkout without a local SDK can still compile and test; see skipEclipseRuntimes().
if (!skipEclipseRuntimes()) {
  tasks.named("assemble") {
    dependsOn(jdtBuildRuntimeZip)
  }
}
