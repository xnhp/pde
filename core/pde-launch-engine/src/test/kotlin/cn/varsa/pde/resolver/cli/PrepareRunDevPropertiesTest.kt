package cn.varsa.pde.resolver.cli

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `pde prepare-run` has to point dev.properties at the folders `pde compile` wrote the classes to. */
class PrepareRunDevPropertiesTest {

  @Test
  fun `dev properties point at the default output folder`() {
    assertDevPropertiesFindCompiledClasses(buildProperties = "source.. = src/\n", expectedRoot = "bin")
  }

  @Test
  fun `dev properties point at the output folder from build properties`() {
    assertDevPropertiesFindCompiledClasses(
      buildProperties = "source.. = src/\noutput.. = target/classes/\n",
      expectedRoot = "target/classes"
    )
  }

  @Test
  fun `fails without writing anything when the output folder holds no classes`() {
    val fixture = Fixture("source.. = src/\n")
    fixture.bundle.resolve("bin").createDirectories()

    assertEquals(1, prepareRunMain(arrayOf("--config", fixture.configFile.toString(), "--output", fixture.outDir.toString())))
    assertFalse(Files.exists(fixture.outDir))
  }

  private fun assertDevPropertiesFindCompiledClasses(buildProperties: String, expectedRoot: String) {
    val fixture = Fixture(buildProperties)
    val configFile = fixture.configFile
    val outDir = fixture.outDir
    val bundle = fixture.bundle

    assertEquals(0, compileMain(arrayOf("--config", configFile.toString())))
    assertEquals(0, prepareRunMain(arrayOf("--config", configFile.toString(), "--output", outDir.toString())))

    val devProperties = Properties().apply { Files.newInputStream(outDir.resolve("dev.properties")).use { load(it) } }
    assertEquals(expectedRoot, devProperties.getProperty("org.example.test"))
    val classFile: Path = bundle.resolve(expectedRoot).resolve("org/example/Foo.class")
    assertTrue(Files.isRegularFile(classFile), "pde compile should have written $classFile")
  }

  /** A profile with only the framework, and one workspace bundle with a Java source and the given build.properties. */
  private class Fixture(buildProperties: String) {
    val baseDir: Path = Files.createTempDirectory("cfg")
    val bundle: Path = Files.createTempDirectory("ws")
    val outDir: Path = baseDir.resolve("runtime")
    val configFile: Path

    init {
      createProfileWithFramework(baseDir)
      bundle.resolve("META-INF").createDirectories().resolve("MANIFEST.MF").writeText(
        """
          Manifest-Version: 1.0
          Bundle-ManifestVersion: 2
          Bundle-SymbolicName: org.example.test
          Bundle-Version: 1.0.0
          Bundle-ClassPath: .
        """.trimIndent() + "\n"
      )
      bundle.resolve("build.properties").writeText(buildProperties)
      bundle.resolve("src/org/example").createDirectories().resolve("Foo.java")
        .writeText("package org.example;\npublic class Foo {}\n")
      configFile = writeConfigFile(baseDir, bundle)
      configFile.appendText("\nlaunches:\n  - name: app\n    application: org.example.app\n")
    }
  }
}
