plugins {
  alias(libs.plugins.kotlin)
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":remote-test-runner"))
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1")
  testImplementation(kotlin("test"))
}

// toolchain/version configured in the root build
