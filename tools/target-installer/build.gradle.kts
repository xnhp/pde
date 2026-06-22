plugins {
  base
}

val launcherJarName = "target-installer-launcher.jar"
val scriptOutput = layout.projectDirectory.file("dist/$launcherJarName")

val buildScriptLauncher by tasks.registering(Exec::class) {
  description = "Build the target-installer Equinox launcher runtime"
  group = "build"
  workingDir = layout.projectDirectory.asFile
  providers.gradleProperty("eclipseSdk").orNull?.let { environment("ECLIPSE_SDK", it) }
  providers.gradleProperty("p2Repositories").orNull?.let { environment("P2_REPOSITORIES", it) }
  providers.gradleProperty("runtimeZip").orNull?.let { environment("RUNTIME_ZIP", it) }
  commandLine("bash", "scripts/build-launcher.sh")
  outputs.file(scriptOutput)
}

val targetInstallerLauncherJar by tasks.registering(Copy::class) {
  description = "Assemble the Gradle-managed target-installer launcher artifact"
  group = "build"
  dependsOn(buildScriptLauncher)
  from(scriptOutput)
  into(layout.buildDirectory.dir("libs"))
}

tasks.named("assemble") {
  dependsOn(targetInstallerLauncherJar)
}
