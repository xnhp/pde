plugins {
  alias(libs.plugins.kotlin)
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":pde-launch-engine"))
  implementation(project(":pde-resolver"))
  implementation(project(":pde-cli-support"))
  implementation(project(":remote-test-runner"))
  implementation(project(":pde-remote-test-runtime"))
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
  testImplementation(kotlin("test"))
}

// toolchain/version configured in the root build
