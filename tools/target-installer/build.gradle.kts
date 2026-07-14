import eclipsep2.equinoxLauncherJar
import eclipsep2.registerPublishAppP2Repo
import eclipsep2.registerMaterializeRuntime
import eclipsep2.registerPinnedRuntimeMaterialize
import eclipsep2.registerRegeneratePinnedRuntimeBundles
import eclipsep2.pinnedRuntimeBundleCacheDir

plugins {
  base
  java
}

repositories {
  mavenCentral()
}

val tinylogLibs by configurations.creating

dependencies {
  tinylogLibs("org.tinylog:tinylog-api:2.7.0")
  tinylogLibs("org.tinylog:tinylog-impl:2.7.0")
}

val eclipseSdkDir = providers.gradleProperty("eclipseSdk").map { file(it) }
val launcherJar = equinoxLauncherJar(eclipseSdkDir)

sourceSets {
  main {
    java.setSrcDirs(listOf("src"))
    resources.setSrcDirs(emptyList<String>())
  }
}

// Application.java/Activator.java use org.eclipse.equinox.internal.* classes not
// exported via normal OSGi visibility rules, so -- same as the previous bash
// javac invocation -- this must compile against a flat classpath of raw jars
// rather than a module-aware dependency set.
configurations.named("compileClasspath") {
  extendsFrom(tinylogLibs)
}
dependencies {
  "compileOnly"(fileTree(eclipseSdkDir.map { it.resolve("plugins") }) { include("*.jar") })
}

val pluginVersion = layout.projectDirectory.file("META-INF/MANIFEST.MF").asFile
  .readLines()
  .first { it.startsWith("Bundle-Version:") }
  .substringAfter("Bundle-Version:").trim()
  .removeSuffix(".qualifier")

val appBsn = "org.knime.targetinstaller"

// FeaturesAndBundlesPublisher wants a directory containing a `plugins/` folder,
// so the app bundle jar is written directly into appBundle/plugins/.
val appBundleDir = layout.buildDirectory.dir("appBundle")

val appBundleJar by tasks.registering(Jar::class) {
  description = "Package target-installer as an OSGi bundle"
  group = "build"
  dependsOn("compileJava")
  archiveFileName = "${appBsn}_$pluginVersion.jar"
  destinationDirectory = appBundleDir.map { it.dir("plugins") }

  manifest.from(layout.projectDirectory.file("META-INF/MANIFEST.MF"))
  from(sourceSets.main.get().output)
  from(layout.projectDirectory.file("plugin.xml"))
  into("lib") { from(tinylogLibs) }
}

val publishAppP2Repo = registerPublishAppP2Repo(
  taskName = "publishAppP2Repo",
  launcherJar = launcherJar,
  sourceDir = appBundleDir,
  outputDir = layout.buildDirectory.dir("app-p2repo")
)
publishAppP2Repo { dependsOn(appBundleJar) }

val hasOsgiServices = eclipseSdkDir.map { sdk ->
  sdk.resolve("plugins").listFiles { f ->
    f.name.startsWith("org.eclipse.osgi.services_") && f.name.endsWith(".jar")
  }?.isNotEmpty() == true
}

val installIUs = hasOsgiServices.map { withServices ->
  buildList {
    add(appBsn)
    add("org.apache.felix.scr")
    add("org.eclipse.equinox.p2.transport.ecf")
    add("org.eclipse.equinox.p2.touchpoint.natives")
    add("org.eclipse.equinox.p2.touchpoint.eclipse")
    add("org.eclipse.equinox.frameworkadmin")
    add("org.eclipse.equinox.frameworkadmin.equinox")
    add("org.eclipse.equinox.simpleconfigurator.manipulator")
    add("org.eclipse.osgi.compatibility.state")
    if (withServices) add("org.eclipse.osgi.services")
  }
}

val sdkP2Repo = rootProject.layout.buildDirectory.dir("sdk-p2repo")
val runtimeDir = layout.buildDirectory.dir("runtime")

// Real p2.director resolution -- manual/CI-only, see docs/pinned-runtime-bundles.md. Feeds
// regeneratePinnedRuntimeBundles below; NOT part of assemble/installDist (materializeRuntime is).
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
  excludeNamePrefixes = provider { listOf("${appBsn}_", "org.eclipse.equinox.launcher_") }
).configure { dependsOn(resolveRuntimeViaP2Director) }

val materializeRuntime = registerPinnedRuntimeMaterialize(
  taskName = "materializeRuntime",
  lockFile = provider { lockFile },
  cacheDir = pinnedBundleCache,
  appBundlePluginsDir = appBundleDir.map { it.dir("plugins") },
  launcherJar = launcherJar,
  destinationDir = runtimeDir
)
materializeRuntime { dependsOn(appBundleJar) }

val runtimeZip by tasks.registering(Zip::class) {
  description = "Archive the materialized runtime for embedding in the launcher jar"
  group = "build"
  dependsOn(materializeRuntime)
  from(runtimeDir)
  archiveFileName = "runtime.zip"
  destinationDirectory = layout.buildDirectory.dir("launcher")
}

val compileBootstrap by tasks.registering(JavaCompile::class) {
  description = "Compile the launcher Bootstrap class"
  group = "build"
  source = fileTree(layout.projectDirectory.dir("launcher/src"))
  classpath = files()
  destinationDirectory = layout.buildDirectory.dir("launcher/classes")
  options.release = 21
}

val targetInstallerLauncherJar by tasks.registering(Jar::class) {
  description = "Assemble the standalone target-installer launcher jar"
  group = "build"
  dependsOn(compileBootstrap, runtimeZip)
  archiveFileName = "target-installer-launcher.jar"
  destinationDirectory = layout.buildDirectory.dir("libs")

  manifest.from(layout.projectDirectory.file("launcher/manifest.mf"))
  from(compileBootstrap.map { it.destinationDirectory })
  from(runtimeZip.flatMap { it.archiveFile })
}

tasks.named("assemble") {
  dependsOn(targetInstallerLauncherJar)
}

// The `java` plugin's default `jar` task packages the same classes redundantly;
// appBundleJar (OSGi bundle) is the artifact we actually want.
tasks.named("jar") {
  enabled = false
}
