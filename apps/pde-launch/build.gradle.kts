import org.gradle.api.tasks.JavaExec

plugins {
  alias(libs.plugins.kotlin)
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation("info.picocli:picocli:4.7.6")
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("org.yaml:snakeyaml:2.6")
  implementation("cn.varsa:cli-core:0.1.0-SNAPSHOT")
  implementation(project(":remote-test-runner"))
  implementation(project(":pde-test-runner"))
  implementation(project(":pde-resolver"))
  implementation(project(":pde-resolver-cli"))
  implementation(project(":pde-format"))
  runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
  testImplementation(kotlin("test"))
}

application {
  mainClass = "cn.varsa.pde.launch.MainKt"
  applicationName = "pde"
}

val generateCliDocs by tasks.registering(JavaExec::class) {
  group = "documentation"
  description = "Generate CLI reference markdown from --help output"
  dependsOn(tasks.named("jar"))
  classpath = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
  mainClass.set("cn.varsa.pde.launch.CliDocsGeneratorKt")
  args(
    rootProject.layout.projectDirectory.file("docs/cli-reference.md").asFile.absolutePath,
    rootProject.layout.projectDirectory.asFile.absolutePath
  )
}

tasks.named("installDist") {
  dependsOn(generateCliDocs)
}

// toolchain/version configured in the root build
