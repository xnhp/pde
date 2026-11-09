import eclipsep2.equinoxLauncherJar
import eclipsep2.registerMaterializeRuntime
import eclipsep2.skipEclipseRuntimes

plugins {
  alias(libs.plugins.kotlin)
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":remote-test-runner"))
  implementation(project(":pde-resolver"))
  implementation(project(":pde-launch-engine"))
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs("-Xms64m", "-Xmx512m")
}

tasks.test {
  useJUnitPlatform()
}

val eclipseSdkDir = rootProject.providers.gradleProperty("eclipseSdk").map { file(it) }
val launcherJar = equinoxLauncherJar(eclipseSdkDir)

val installIUs = provider {
  listOf(
    "org.eclipse.jdt.core",
    "org.eclipse.text",
    "org.eclipse.jface.text"
  )
}

val sdkP2Repo = rootProject.layout.buildDirectory.dir("sdk-p2repo")
val formatterRuntimeDir = layout.buildDirectory.dir("formatter-runtime")

val materializeFormatterRuntime = registerMaterializeRuntime(
  taskName = "materializeFormatterRuntime",
  launcherJar = launcherJar,
  repositoryDirs = provider { listOf(sdkP2Repo.get().asFile) },
  extraRepositories = rootProject.providers.gradleProperty("p2Repositories"),
  installIUs = installIUs,
  destinationDir = formatterRuntimeDir
)
materializeFormatterRuntime {
  dependsOn(rootProject.tasks.named("publishSdkP2Repo"))
}

val formatterRuntimeZip by tasks.registering(Zip::class) {
  description = "Archive the formatter runtime (Eclipse JDT jars)"
  group = "build"
  dependsOn(materializeFormatterRuntime)
  from(formatterRuntimeDir)
  archiveFileName = "formatter-runtime.zip"
  destinationDirectory = layout.buildDirectory.dir("libs")
}

// Embedding the runtime archive here is what couples `check` to a local Eclipse SDK: the unit
// tests in src/test never load it (FormatterRuntimeBootstrap only reads it at runtime), but the
// resource lands on the test runtime classpath, so `test` transitively requires p2.director.
// -PskipEclipseRuntimes=true unhooks it for test-only builds; see skipEclipseRuntimes().
if (!skipEclipseRuntimes()) {
  tasks.named<ProcessResources>("processResources") {
    from(formatterRuntimeZip)
  }

  tasks.named("assemble") {
    dependsOn(formatterRuntimeZip)
  }
}
