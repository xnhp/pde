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
  environment("ECLIPSE_SDK", providers.gradleProperty("eclipseSdk").get())
  environment("P2_REPOSITORIES", providers.gradleProperty("p2Repositories").get())
  commandLine("bash", "scripts/build-launcher.sh")
  outputs.file(file("tools/target-installer/dist/target-installer-launcher.jar"))
}
