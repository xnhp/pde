plugins {
  alias(libs.plugins.kotlin)
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":remote-test-runner"))
  testImplementation(kotlin("test"))
}

// toolchain/version configured in the root build
