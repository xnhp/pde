plugins {
  alias(libs.plugins.kotlin)
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation("cn.varsa:cli-core:0.1.0-SNAPSHOT")
  implementation(project(":pde-resolver"))
  implementation(project(":pde-cli-support"))
  implementation(project(":remote-test-runner"))
  implementation(project(":pde-remote-test-runtime"))
  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
  implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
  runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
  // Bundled for `pde …​ --coverage` record: a recent, Java-21-clean JaCoCo agent.
  // (the target's own org.knime.testing/lib/jacocoagent.jar is too old — aborts at premain).
  runtimeOnly("org.jacoco:org.jacoco.agent:0.8.12:runtime")
  testImplementation(kotlin("test"))
}

// toolchain/version configured in the root build
