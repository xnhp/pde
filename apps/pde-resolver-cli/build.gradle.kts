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
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
  testImplementation(kotlin("test"))
}

application {
  mainClass = "cn.varsa.pde.resolver.cli.MainKt"
}
// toolchain/version configured in the root build
