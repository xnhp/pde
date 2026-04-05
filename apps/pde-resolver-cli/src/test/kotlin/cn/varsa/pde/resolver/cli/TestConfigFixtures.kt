package cn.varsa.pde.resolver.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

fun createProfileWithFramework(
  baseDir: Path,
  profileId: String = "profile",
  p2Root: Path = baseDir.resolve("target").resolve("p2")
): Path {
  val registry = p2Root
    .resolve("org.eclipse.equinox.p2.engine")
    .resolve("profileRegistry")
    .resolve("$profileId.Profile")
    .createDirectories()
  val pool = p2Root.resolve("bundle-pool").createDirectories()
  val plugins = pool.resolve("plugins").createDirectories()
  createFrameworkJar(plugins)
  val profileFile = registry.resolve("1.profile")
  profileFile.writeText(
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <profile id="$profileId" timestamp="1" version="1.0.0">
        <properties>
          <property name="org.eclipse.equinox.p2.cache" value="${pool.toUri()}"/>
        </properties>
      </profile>
    """.trimIndent()
  )
  return registry
}

fun writeConfigFile(
  baseDir: Path,
  workspace: Path,
  fileName: String = "pde.yaml",
  profileId: String = "profile",
  p2Path: String = "target/p2"
): Path {
  val repoDir = workspace.parent ?: workspace
  val bundleName = workspace.fileName?.toString().orEmpty().ifBlank { workspace.toString() }
  val configFile = baseDir.resolve(fileName)
  configFile.writeText(
    """
      target:
        profileId: $profileId
        p2Path: $p2Path
      bundles:
        - path: ${repoDir.toAbsolutePath()}/$bundleName
    """.trimIndent()
  )
  return configFile
}

private fun createFrameworkJar(pluginsDir: Path) {
  val mf = Manifest().apply {
    mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
    mainAttributes.putValue("Bundle-ManifestVersion", "2")
    mainAttributes.putValue("Bundle-SymbolicName", "org.eclipse.osgi")
    mainAttributes.putValue("Bundle-Version", "1.0.0")
  }
  JarOutputStream(Files.newOutputStream(pluginsDir.resolve("org.eclipse.osgi_1.0.0.jar")), mf).use { /* empty */ }
}
