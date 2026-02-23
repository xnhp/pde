import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
  alias(libs.plugins.kotlin)
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":pde-resolver"))
  implementation(project(":pde-test-runner"))
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1")
  testImplementation(kotlin("test"))
}

application {
  mainClass = "cn.varsa.pde.resolver.cli.MainKt"
}
// toolchain/version configured in the root build

tasks.named<CreateStartScripts>("startScripts") {
  outputDir = layout.buildDirectory.dir("scripts/main").get().asFile
}

val compileStartScripts = tasks.register<CreateStartScripts>("compileStartScripts") {
  mainClass.set("cn.varsa.pde.resolver.cli.CompileMainKt")
  applicationName = "pde-compile"
  classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
  outputDir = layout.buildDirectory.dir("scripts/compile").get().asFile
}

val cleanLegacyCompileDist = tasks.register<Delete>("cleanLegacyCompileDist") {
  delete(layout.buildDirectory.dir("install/pde-compile"))
}

distributions {
  main {
    contents {
      from(compileStartScripts) {
        into("bin")
      }
    }
  }
}

tasks.named("installDist") {
  dependsOn(compileStartScripts)
  dependsOn(cleanLegacyCompileDist)
}
