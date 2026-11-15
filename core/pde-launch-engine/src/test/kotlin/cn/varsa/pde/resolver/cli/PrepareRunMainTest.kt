package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrepareRunMainTest {

  @Test
  fun `writes every target bundle with the workspace bundle in place of its target copy`() {
    val fixture = Fixture()

    val exitCode = prepareRunMain(arrayOf("--config", fixture.configFile.toString(), "--output", fixture.outDir.toString()))

    assertEquals(0, exitCode)
    val bundlesInfo = fixture.bundlesInfo()
    assertTrue(bundlesInfo.contains("org.example.unrelated,"), bundlesInfo)
    val workspaceLine = bundlesInfo.lines().single { it.startsWith("org.example.test,") }
    assertFalse(workspaceLine.contains("bundle-pool"), "the workspace bundle must not come from the target: $workspaceLine")
  }

  @Test
  fun `config ini carries the launch's application and points osgi dev at the written dev properties`() {
    val fixture = Fixture()

    val exitCode = prepareRunMain(arrayOf(fixture.configFile.toString(), "app", "-o", fixture.outDir.toString()))

    assertEquals(0, exitCode)
    val configIni = fixture.configIni()
    assertEquals("org.example.app", configIni.getProperty("eclipse.application"))
    val devProperties = fixture.outDir.resolve("dev.properties")
    assertEquals(devProperties.toUri().toString(), configIni.getProperty("osgi.dev"))
    assertEquals(
      fixture.outDir.resolve("org.eclipse.equinox.simpleconfigurator/bundles.info").toUri().toString(),
      configIni.getProperty("org.eclipse.equinox.simpleconfigurator.configUrl")
    )
    assertTrue(Files.exists(devProperties))
  }

  @Test
  fun `selects a launch by its 1-based index`() {
    val fixture = Fixture()

    val exitCode = prepareRunMain(arrayOf("--config", fixture.configFile.toString(), "2", "--output", fixture.outDir.toString()))

    assertEquals(0, exitCode)
    assertEquals("org.example.other", fixture.configIni().getProperty("eclipse.application"))
  }

  @Test
  fun `unknown launch writes nothing and fails`() {
    val fixture = Fixture()

    val exitCode = prepareRunMain(
      arrayOf("--config", fixture.configFile.toString(), "missing", "--output", fixture.outDir.toString())
    )

    assertEquals(2, exitCode)
    assertFalse(Files.exists(fixture.outDir.resolve("config.ini")))
  }

  @Test
  fun `falls back to the launch's configDir when --output is omitted`() {
    val fixture = Fixture()

    val exitCode = prepareRunMain(arrayOf("--config", fixture.configFile.toString(), "withConfigDir"))

    assertEquals(0, exitCode)
    val configIni = Properties().apply {
      Files.newInputStream(fixture.baseDir.resolve("configdir-output/config.ini")).use { load(it) }
    }
    assertEquals("org.example.app", configIni.getProperty("eclipse.application"))
  }

  @Test
  fun `fails when neither --output nor the launch's configDir is set`() {
    val fixture = Fixture()

    val exitCode = prepareRunMain(arrayOf("--config", fixture.configFile.toString(), "app"))

    assertEquals(2, exitCode)
    assertFalse(Files.exists(fixture.outDir.resolve("config.ini")))
  }

  /** A profile with the framework and one bundle nothing depends on, and a workspace bundle with two launches. */
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
      workspace.resolve("META-INF").createDirectories().resolve("MANIFEST.MF").writeText(
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
            - name: other
              application: org.example.other
            - name: withConfigDir
              application: org.example.app
              configDir: configdir-output
        """.trimIndent() + "\n"
      )
    }

    fun bundlesInfo(): String = outDir.resolve("org.eclipse.equinox.simpleconfigurator/bundles.info").readText()

    fun configIni(): Properties = Properties().apply {
      Files.newInputStream(outDir.resolve("config.ini")).use { load(it) }
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
