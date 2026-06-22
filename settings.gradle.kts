rootProject.name = "pde-tools"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// cli-core is included as a Git submodule in libs/cli-core.
// Run `git submodule update --init` to populate it.
// Gradle will substitute the cn.varsa:cli-core Maven coordinate with this local build.
if (file("libs/cli-core").exists()) {
  includeBuild("libs/cli-core")
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
