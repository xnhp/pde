plugins {
  alias(libs.plugins.kotlin)
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("org.yaml:snakeyaml:2.2")
  implementation(project(":pde-test-runner"))
  implementation(project(":pde-resolver"))
  implementation(project(":pde-resolver-cli"))
  implementation(project(":pde-format"))
  testImplementation(kotlin("test"))
}

application {
  mainClass = "cn.varsa.pde.launch.MainKt"
  applicationName = "pde"
}

// toolchain/version configured in the root build
