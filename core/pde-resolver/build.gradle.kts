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
  api("org.osgi:osgi.core:8.0.0")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
  implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.45.0")
  implementation("org.eclipse.pde:org.eclipse.pde.api.tools:1.3.600")

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}
