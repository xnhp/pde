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
  implementation(project(":pde-resolver"))
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
  testImplementation(kotlin("test"))
}

application {
  mainClass = "cn.varsa.pde.resolver.cli.MainKt"
}
kotlin {
  jvmToolchain(21)
}
