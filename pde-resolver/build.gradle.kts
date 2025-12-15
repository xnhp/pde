plugins {
  alias(libs.plugins.kotlin)
}

group = "cn.varsa"
version = "0.1.0-SNAPSHOT"

kotlin {
  jvmToolchain(21)
}

repositories {
  mavenCentral()
}

dependencies {
  // Root disables default stdlib; add explicitly
  implementation(kotlin("stdlib"))
  api("org.osgi:org.osgi.core:6.0.0")

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}
