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

val runtimeArchive = layout.projectDirectory.file("dist/api-analyzer-runtime.zip")

val buildRuntime by tasks.registering(Exec::class) {
  description = "Build the API analyzer Equinox runtime"
  group = "build"
  workingDir = layout.projectDirectory.asFile
  providers.gradleProperty("eclipseSdk").orNull?.let { environment("ECLIPSE_SDK", it) }
  providers.gradleProperty("p2Repositories").orNull?.let { environment("P2_REPOSITORIES", it) }
  inputs.files(appLibs)
  environment("APP_LIBS", appLibs.files.joinToString(File.pathSeparator) { it.absolutePath })
  commandLine("bash", "scripts/build-runtime.sh")
  outputs.file(runtimeArchive)
}

val apiAnalyzerRuntimeZip by tasks.registering(Copy::class) {
  description = "Assemble the Gradle-managed API analyzer runtime zip"
  group = "build"
  dependsOn(buildRuntime)
  from(runtimeArchive)
  into(layout.buildDirectory.dir("libs"))
}

tasks.named("assemble") {
  dependsOn(apiAnalyzerRuntimeZip)
}
