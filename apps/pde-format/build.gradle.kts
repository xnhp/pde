plugins {
  alias(libs.plugins.kotlin)
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":remote-test-runner"))
  implementation(project(":pde-resolver"))
  implementation(project(":pde-resolver-cli"))
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

application {
  mainClass = "pde.format.MainKt"
  applicationName = "pde-format"
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs("-Xms64m", "-Xmx512m")
}

tasks.test {
  useJUnitPlatform()
}
