plugins {
  alias(libs.plugins.kotlin)
  application
}

group = "cn.varsa"
version = "0.1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":remote-test-runner"))
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
  testImplementation(kotlin("test"))
}

application {
  mainClass = "cn.varsa.pde.remoterunner.MainKt"
}

kotlin {
  jvmToolchain(21)
}
