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
  implementation(project(":pde-resolver"))
  implementation(project(":pde-resolver-cli"))
  testImplementation(kotlin("test"))
}

application {
  mainClass = "cn.varsa.pde.launch.MainKt"
}

// toolchain/version configured in the root build
