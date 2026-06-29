rootProject.name = "pde-tools"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Gradle substitutes cn.varsa:cli-core with a local source checkout when available.
// Use -PcliCorePath=/path/to/cli-core to force a specific checkout. Otherwise a
// sibling ../cli-core checkout wins over the optional libs/cli-core submodule.
val cliCorePath = providers.gradleProperty("cliCorePath").orNull
val cliCoreBuild = listOfNotNull(
  cliCorePath?.let { file(it) },
  file("../cli-core"),
  file("libs/cli-core")
).firstOrNull { it.exists() }

if (cliCoreBuild != null) {
  includeBuild(cliCoreBuild)
}

include(":intellij")

// Core resolver module for target platform parsing/indexing
include(":pde-resolver")
include(":pde-launch-engine")
include(":remote-test-runner")
include(":pde-remote-test-runtime")
include(":pde-cli")
include(":pde-format")
include(":target-installer")

project(":intellij").projectDir = file("intellij")
project(":pde-resolver").projectDir = file("core/pde-resolver")
project(":remote-test-runner").projectDir = file("core/remote-test-runner")
project(":pde-launch-engine").projectDir = file("core/pde-launch-engine")
project(":pde-remote-test-runtime").projectDir = file("core/pde-remote-test-runtime")
project(":pde-cli").projectDir = file("apps/pde-launch")
project(":pde-format").projectDir = file("apps/pde-format")
project(":target-installer").projectDir = file("tools/target-installer")
