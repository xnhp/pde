import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  alias(libs.plugins.kotlin) apply false
}

allprojects {
  group = providers.gradleProperty("pluginGroup").orNull ?: "cn.varsa"
  version = providers.gradleProperty("pluginVersion").orNull ?: "0.0.0"
}

val githubPackagesUsername = providers.gradleProperty("gpr.user")
  .orElse(providers.environmentVariable("GITHUB_ACTOR"))
val githubPackagesPassword = providers.gradleProperty("gpr.key")
  .orElse(providers.environmentVariable("GITHUB_TOKEN"))

subprojects {
  repositories {
    mavenLocal()
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/xnhp/cli-core")
      credentials {
        username = githubPackagesUsername.orNull
        password = githubPackagesPassword.orNull
      }
      content {
        includeGroup("cn.varsa")
      }
    }
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
