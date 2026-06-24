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
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  runtimeOnly("org.jacoco:org.jacoco.cli:0.8.12:nodeps")
  testImplementation(kotlin("test"))
}

// toolchain/version configured in the root build
