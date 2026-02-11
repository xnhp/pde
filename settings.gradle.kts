rootProject.name = "pde-tools"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":intellij")

// Core resolver module for target platform parsing/indexing
include(":pde-resolver")
include(":pde-resolver-cli")
include(":remote-test-runner")
include(":pde-remote-runner")
include(":pde-launch")

project(":intellij").projectDir = file("intellij")
project(":pde-resolver").projectDir = file("core/pde-resolver")
project(":remote-test-runner").projectDir = file("core/remote-test-runner")
project(":pde-resolver-cli").projectDir = file("apps/pde-resolver-cli")
project(":pde-remote-runner").projectDir = file("apps/pde-remote-runner")
project(":pde-launch").projectDir = file("apps/pde-launch")
