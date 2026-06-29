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
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/xnhp/cli-core")
      credentials {
        username = providers.gradleProperty("gpr.user")
          .orElse(providers.environmentVariable("GITHUB_ACTOR"))
          .orNull
        password = providers.gradleProperty("gpr.key")
          .orElse(providers.environmentVariable("GITHUB_TOKEN"))
          .orNull
      }
    }
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
