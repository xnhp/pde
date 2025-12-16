plugins {
  alias(libs.plugins.kotlin)
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  testImplementation(kotlin("test"))
}

// toolchain/version configured in the root build
