plugins {
  alias(libs.plugins.kotlin)
}

group = "cn.varsa"
version = "0.1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  testImplementation(kotlin("test"))
}

kotlin {
  jvmToolchain(21)
}
