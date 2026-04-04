package cn.varsa.pde.launch

import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AddTestHelperCommandTest {
  private val originalUserDir = System.getProperty("user.dir")
  private val originalOut = System.out

  @AfterTest
  fun tearDown() {
    System.setProperty("user.dir", originalUserDir)
    System.setOut(originalOut)
  }

  @Test
  fun `adds helper entry with methods`() {
    val tempDir = Files.createTempDirectory("pde-add-test-helper")
    val configPath = tempDir.resolve("pde.yaml")
    Files.writeString(
      configPath,
      """
      bundles: []
      """.trimIndent()
    )

    System.setProperty("user.dir", tempDir.toString())
    val stdout = ByteArrayOutputStream()
    System.setOut(PrintStream(stdout))

    val exitCode = AddTestHelperCommand.main(
      arrayOf(
        "org.example.FooTests",
        "testOne,testTwo"
      )
    )

    assertEquals(0, exitCode)

    val rootMap = readYamlMap(Files.readString(configPath))
    val tests = rootMap["tests"] as? List<*>
    assertNotNull(tests)
    assertEquals(1, tests.size)

    val entry = tests[0] as? Map<*, *>
    assertNotNull(entry)
    assertEquals("org.knime.gateway.impl", entry["testPluginName"])
    assertEquals("org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests", entry["className"])
    val vmArgs = entry["vmArgs"] as? List<*>
    assertNotNull(vmArgs)
    assertEquals(
      listOf(
        "-Dorg.knime.gateway.testing.helper.test_class=org.example.FooTests",
        "-Dorg.knime.gateway.testing.helper.test_method=testOne,testTwo"
      ),
      vmArgs
    )

    assertEquals("Added test helper entry to pde.yaml\n", stdout.toString())
  }

  @Test
  fun `adds helper entry without methods`() {
    val tempDir = Files.createTempDirectory("pde-add-test-helper")
    val configPath = tempDir.resolve("pde.yaml")
    Files.writeString(
      configPath,
      """
      bundles: []
      """.trimIndent()
    )

    System.setProperty("user.dir", tempDir.toString())
    val stdout = ByteArrayOutputStream()
    System.setOut(PrintStream(stdout))

    val exitCode = AddTestHelperCommand.main(
      arrayOf("org.example.FooTests")
    )

    assertEquals(0, exitCode)

    val rootMap = readYamlMap(Files.readString(configPath))
    val tests = rootMap["tests"] as? List<*>
    assertNotNull(tests)
    assertEquals(1, tests.size)

    val entry = tests[0] as? Map<*, *>
    assertNotNull(entry)
    val vmArgs = entry["vmArgs"] as? List<*>
    assertNotNull(vmArgs)
    assertEquals(
      listOf(
        "-Dorg.knime.gateway.testing.helper.test_class=org.example.FooTests"
      ),
      vmArgs
    )

    assertEquals("Added test helper entry to pde.yaml\n", stdout.toString())
  }

  private fun readYamlMap(contents: String): Map<*, *> {
    val yaml = Yaml()
    return yaml.load<Any>(contents) as Map<*, *>
  }
}
