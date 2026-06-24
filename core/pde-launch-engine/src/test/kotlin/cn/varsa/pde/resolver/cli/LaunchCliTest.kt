package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LaunchCliTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun launchCommandGeneratesOutputs() {
    val target = tmp.newFolder("target").also { createBundle(it, "org.eclipse.osgi", "1.0.0") }
    val workspace = tmp.newFolder("workspace").also {
      createBundle(it, "org.example.app", "1.0.0", require = "org.eclipse.osgi")
    }
    val output = tmp.newFolder("out")

    launchMain(
      arrayOf(
        "--target-root", target.absolutePath,
        "--workspace", workspace.absolutePath,
        "--dev-prop", "org.example.app=${workspace.absolutePath}/classes",
        "--product", "org.example.product",
        "--application", "org.example.app",
        "--output", output.absolutePath
      )
    )

    assertTrue(File(output, "config.ini").exists())
    assertTrue(File(output, "org.eclipse.equinox.simpleconfigurator/bundles.info").exists())
    assertTrue(File(output, "dev.properties").exists())
  }

  @Test
  fun envFileJavaHomeSelectsLaunchJavaExecutable() {
    val root = tmp.root.toPath()
    val workspace = tmp.newFolder("workspace").also {
      createBundle(it, "org.example.app", "1.0.0", require = "org.eclipse.osgi")
    }
    createProfileWithFramework(root)
    createBundleJar(root.resolve("target/p2/bundle-pool/plugins"), "org.eclipse.equinox.launcher", "1.0.0")
    writeProfile(root, listOf("org.eclipse.osgi" to "1.0.0", "org.eclipse.equinox.launcher" to "1.0.0"))

    val javaHome = root.resolve("fake-java-home")
    val javaBinDir = javaHome.resolve("bin").createDirectories()
    val javaExecutable = javaBinDir.resolve("java")
    val capturedJavaHome = root.resolve("captured-java-home.txt")
    javaExecutable.writeText(
      """
        #!/bin/sh
        printf '%s\n' "${'$'}JAVA_HOME" > '${capturedJavaHome.toAbsolutePath()}'
        exit 0
      """.trimIndent()
    )
    assertTrue(javaExecutable.toFile().setExecutable(true))

    root.resolve(".env").writeText("JAVA_HOME=${javaHome.toAbsolutePath()}\n")
    val configFile = root.resolve("pde.yaml")
    configFile.writeText(
      """
        target:
          profileId: profile
          p2Path: target/p2
        bundles:
          - path: ${workspace.absolutePath}
        launches:
          - name: run
            application: org.example.app
            envFile: .env
      """.trimIndent()
    )

    launchMain(arrayOf("--config", configFile.toString()))

    assertEquals(javaHome.toAbsolutePath().toString(), capturedJavaHome.readText().trim())
  }

  private fun createBundle(root: File, bsn: String, version: String, require: String? = null) {
    val metaInf = File(root, "META-INF")
    metaInf.mkdirs()
    val mf = File(metaInf, "MANIFEST.MF")
    val headers = buildString {
      appendLine("Manifest-Version: 1.0")
      appendLine("Bundle-SymbolicName: $bsn")
      appendLine("Bundle-Version: $version")
      if (require != null) appendLine("Require-Bundle: $require")
    }
    mf.writeText(headers)
  }

  private fun createBundleJar(pluginsDir: Path, bsn: String, version: String) {
    Files.createDirectories(pluginsDir)
    val mf = Manifest().apply {
      mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
      mainAttributes.putValue("Bundle-ManifestVersion", "2")
      mainAttributes.putValue("Bundle-SymbolicName", bsn)
      mainAttributes.putValue("Bundle-Version", version)
    }
    JarOutputStream(Files.newOutputStream(pluginsDir.resolve("${bsn}_$version.jar")), mf).use { /* empty */ }
  }

  private fun writeProfile(root: Path, bundles: List<Pair<String, String>>) {
    val bundlePool = root.resolve("target/p2/bundle-pool")
    root.resolve("target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.Profile/1.profile").writeText(
      buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<profile id="profile" timestamp="1" version="1.0.0">""")
        appendLine("  <properties>")
        appendLine("""    <property name="org.eclipse.equinox.p2.cache" value="${bundlePool.toUri()}"/>""")
        appendLine("  </properties>")
        appendLine("  <artifacts>")
        bundles.forEach { (bsn, version) ->
          appendLine("""    <artifact classifier="osgi.bundle" id="$bsn" version="$version"/>""")
        }
        appendLine("  </artifacts>")
        appendLine("</profile>")
      }
    )
  }
}
