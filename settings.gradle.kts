rootProject.name = "eclipse-pde-partial-idea"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Core resolver module for target platform parsing/indexing
include(":pde-resolver")
include(":pde-resolver-cli")
include(":remote-test-runner")
include(":pde-remote-runner")
