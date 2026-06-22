plugins {
  alias(libs.plugins.kotlin)
  application
}

evaluationDependsOn(":target-installer")

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation("info.picocli:picocli:4.7.6")
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("org.yaml:snakeyaml:2.6")
  implementation("cn.varsa:cli-core:0.1.0-SNAPSHOT")
  implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
  implementation(project(":remote-test-runner"))
  implementation(project(":pde-remote-test-runtime"))
  implementation(project(":pde-resolver"))
  implementation(project(":pde-launch-engine"))
  implementation(project(":pde-format"))
  runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
  testImplementation(kotlin("test"))
}

application {
  mainClass = "cn.varsa.pde.launch.MainKt"
  applicationName = "pde"
}

distributions {
  main {
    contents {
      val targetInstallerProject = project(":target-installer")
      from(targetInstallerProject.layout.buildDirectory.file("libs/target-installer-launcher.jar")) {
        into("lib")
      }
    }
  }
}

listOf("assembleDist", "distTar", "distZip", "installDist").forEach { taskName ->
  tasks.named(taskName) {
    dependsOn(":target-installer:targetInstallerLauncherJar")
  }
}

val mcpStartScript = layout.buildDirectory.file("mcp/pde-mcp")

tasks.register("mcpStartScripts") {
  notCompatibleWithConfigurationCache("Generates a local absolute-classpath launcher script.")
  dependsOn(tasks.named("jar"))
  inputs.files(tasks.named<Jar>("jar"), configurations.runtimeClasspath)
  outputs.file(mcpStartScript)
  doLast {
    val classpath = (files(tasks.named<Jar>("jar")) + configurations.runtimeClasspath.get())
      .joinToString(":") { it.absolutePath }
    val script = mcpStartScript.get().asFile
    script.parentFile.mkdirs()
    script.writeText(
      """#!/usr/bin/env sh
exec java -cp '$classpath' cn.varsa.pde.launch.PdeMcpServerKt "$@"
"""
    )
    script.setExecutable(true)
  }
}

// toolchain/version configured in the root build
