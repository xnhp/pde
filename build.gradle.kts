import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  alias(libs.plugins.kotlin) apply false
}

allprojects {
  group = providers.gradleProperty("pluginGroup").orNull ?: "cn.varsa"
  version = providers.gradleProperty("pluginVersion").orNull ?: "0.0.0"
}

subprojects {
  repositories {
    mavenLocal()
    mavenCentral()
  }

  plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<KotlinJvmProjectExtension>("kotlin") {
      jvmToolchain(21)
    }
  }
}

tasks.wrapper {
  gradleVersion = providers.gradleProperty("gradleVersion").get()
}

tasks.register<Exec>("buildTargetInstallerLauncher") {
  description = "Build the standalone target-installer launcher jar"
  group = "build"
  workingDir = file("tools/target-installer")
  providers.gradleProperty("eclipseSdk").orNull?.let { environment("ECLIPSE_SDK", it) }
  providers.gradleProperty("p2Repositories").orNull?.let { environment("P2_REPOSITORIES", it) }
  providers.gradleProperty("runtimeZip").orNull?.let { environment("RUNTIME_ZIP", it) }
  commandLine("bash", "scripts/build-launcher.sh")
  outputs.file(file("tools/target-installer/dist/target-installer-launcher.jar"))
}
