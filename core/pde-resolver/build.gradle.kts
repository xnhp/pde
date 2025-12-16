plugins {
  alias(libs.plugins.kotlin)
}

// toolchain/version configured in the root build

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
