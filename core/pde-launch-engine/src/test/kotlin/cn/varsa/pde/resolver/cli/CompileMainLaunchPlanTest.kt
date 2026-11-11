package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompileMainLaunchPlanTest {

  @Test
  fun `runtime-out without launch writes only the workspace closure`() {
    val fixture = Fixture()

    val exitCode = compileMain(arrayOf("--config", fixture.configFile.toString(), "--runtime-out", fixture.outDir.toString()))

    assertEquals(0, exitCode)
    val bundlesInfo = fixture.bundlesInfo()
    assertTrue(bundlesInfo.contains("org.example.test,"), bundlesInfo)
    assertFalse(bundlesInfo.contains("org.example.unrelated,"), bundlesInfo)
  }

  @Test
  fun `runtime-out with launch writes every target bundle like pde run`() {
    val fixture = Fixture()

    val exitCode = compileMain(
      arrayOf("--config", fixture.configFile.toString(), "--runtime-out", fixture.outDir.toString(), "--launch", "app")
    )

    assertEquals(0, exitCode)
    val bundlesInfo = fixture.bundlesInfo()
    assertTrue(bundlesInfo.contains("org.example.unrelated,"), bundlesInfo)
    val workspaceLine = bundlesInfo.lines().single { it.startsWith("org.example.test,") }
    assertFalse(workspaceLine.contains("bundle-pool"), "the workspace bundle must point at its own outputs: $workspaceLine")
    val configIni = Files.walk(fixture.outDir).use { paths ->
      paths.asSequence().single { it.fileName.toString() == "config.ini" }.readText()
    }
    assertTrue(configIni.contains("eclipse.application=org.example.app"), configIni)
  }

  @Test
  fun `bundles-info-out with launch writes every target bundle`() {
    val fixture = Fixture()
    val bundlesInfoFile = fixture.baseDir.resolve("bundles.info")

    val exitCode = compileMain(
      arrayOf("--config", fixture.configFile.toString(), "--bundles-info-out", bundlesInfoFile.toString(), "--launch", "1")
    )

    assertEquals(0, exitCode)
    assertTrue(bundlesInfoFile.readText().contains("org.example.unrelated,"))
  }

  @Test
  fun `launch without an output to write is a usage error`() {
    val fixture = Fixture()

    val exitCode = compileMain(arrayOf("--config", fixture.configFile.toString(), "--launch", "app"))

    assertEquals(2, exitCode)
  }

  @Test
  fun `unknown launch is a usage error`() {
    val fixture = Fixture()

    val exitCode = compileMain(
      arrayOf("--config", fixture.configFile.toString(), "--runtime-out", fixture.outDir.toString(), "--launch", "missing")
    )

    assertEquals(2, exitCode)
    assertFalse(Files.exists(fixture.outDir.resolve("config.ini")))
  }

  /** A profile with the framework and one bundle nothing depends on, and a workspace bundle with one launch. */
  private class Fixture {
    val baseDir: Path = Files.createTempDirectory("cfg")
    val outDir: Path = baseDir.resolve("runtime")
    val configFile: Path

    init {
      val workspace = Files.createTempDirectory("ws")
      val registry = createProfileWithFramework(baseDir)
      val pool = baseDir.resolve("target/p2/bundle-pool")
      writeBundleJar(pool.resolve("plugins"), "org.example.unrelated")
      // The target index holds only the bundles the profile lists as installed artifacts.
      registry.resolve("1.profile").writeText(
        """
          <?xml version="1.0" encoding="UTF-8"?>
          <profile id="profile" timestamp="1" version="1.0.0">
            <properties>
              <property name="org.eclipse.equinox.p2.cache" value="${pool.toUri()}"/>
            </properties>
            <artifacts size="2">
              <artifact classifier="osgi.bundle" id="org.eclipse.osgi" version="1.0.0"/>
              <artifact classifier="osgi.bundle" id="org.example.unrelated" version="1.0.0"/>
            </artifacts>
          </profile>
        """.trimIndent()
      )
      val meta = workspace.resolve("META-INF").createDirectories()
      meta.resolve("MANIFEST.MF").writeText(
        """
          Manifest-Version: 1.0
          Bundle-ManifestVersion: 2
          Bundle-SymbolicName: org.example.test
          Bundle-Version: 1.0.0
          Bundle-ClassPath: .
        """.trimIndent() + "\n"
      )
      workspace.resolve("src").createDirectories().resolve("Foo.java").writeText("public class Foo {}")
      configFile = writeConfigFile(baseDir, workspace)
      configFile.appendText(
        "\n" + """
          launches:
            - name: app
              application: org.example.app
        """.trimIndent() + "\n"
      )
    }

    fun bundlesInfo(): String = Files.walk(outDir).use { paths ->
      paths.asSequence().single { it.fileName.toString() == "bundles.info" }.readText()
    }

    private fun writeBundleJar(pluginsDir: Path, bsn: String) {
      val mf = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes.putValue("Bundle-ManifestVersion", "2")
        mainAttributes.putValue("Bundle-SymbolicName", bsn)
        mainAttributes.putValue("Bundle-Version", "1.0.0")
      }
      JarOutputStream(Files.newOutputStream(pluginsDir.resolve("${bsn}_1.0.0.jar")), mf).use { /* empty */ }
    }
  }
}
