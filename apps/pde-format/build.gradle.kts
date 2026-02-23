plugins {
  alias(libs.plugins.kotlin)
  application
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(project(":pde-resolver-cli"))
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
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
