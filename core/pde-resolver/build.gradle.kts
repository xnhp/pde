import eclipsep2.pinnedRuntimeBundleCacheDir
import eclipsep2.pinnedRuntimeBundleJar

plugins {
  alias(libs.plugins.kotlin)
}

// toolchain/version configured in the root build

repositories {
  mavenCentral()
}

// DirectApiAnalyzerHarness compiles against org.eclipse.pde.api.tools' internal (non-exported)
// packages, so an independently-versioned Maven coordinate here can silently drift from whatever
// version the runtime actually loads (see EX-106 follow-up: the NoSuchMethodError this caused).
// Source the compile-time jar from the same pinned bundle set :api-analyzer's runtime uses (see
// pinnedRuntimeBundleJar() in buildSrc), so a runtime-bundles.lock bump can't leave this on a
// different version.
val eclipseSdkDir = providers.gradleProperty("eclipseSdk").map { file(it) }
val apiAnalyzerLockFile = rootProject.layout.projectDirectory.file("tools/api-analyzer/runtime-bundles.lock")
val apiToolsJar = pinnedRuntimeBundleJar(
  lockFile = provider { apiAnalyzerLockFile },
  cacheDir = pinnedRuntimeBundleCacheDir(),
  sdkDir = eclipseSdkDir,
  namePrefix = "org.eclipse.pde.api.tools_"
)

dependencies {
  // Root disables default stdlib; add explicitly
  implementation(kotlin("stdlib"))
  api("org.osgi:osgi.core:8.0.0")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
  implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.45.0")
  // Pulled in transitively by org.eclipse.pde.api.tools before; declared explicitly now that
  // api.tools itself comes from the pinned jar below instead of Maven. Their own versions aren't
  // the drift-prone part (DirectApiAnalyzerHarness only reaches into api.tools' internals).
  implementation("org.eclipse.pde:org.eclipse.pde.core:3.20.0")
  implementation("org.eclipse.jdt:org.eclipse.jdt.launching:3.23.100")
  // Not linked into any jar we ship: at runtime this bundle is supplied by the OSGi
  // Require-Bundle wiring (see tools/api-analyzer/build.gradle.kts), never a flat classpath jar.
  compileOnly(files(apiToolsJar))

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}
