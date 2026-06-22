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

tasks.register("buildTargetInstallerLauncher") {
  description = "Build the standalone target-installer launcher jar"
  group = "build"
  dependsOn(":target-installer:targetInstallerLauncherJar")
}
