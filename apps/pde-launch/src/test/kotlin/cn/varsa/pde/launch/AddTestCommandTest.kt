package cn.varsa.pde.launch

import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AddTestCommandTest {
  private val originalUserDir = System.getProperty("user.dir")
  private val originalOut = System.out

  @AfterTest
  fun tearDown() {
    System.setProperty("user.dir", originalUserDir)
    System.setOut(originalOut)
  }

  @Test
  fun `adds test entry to config yaml`() {
    val tempDir = Files.createTempDirectory("pde-add-test")
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

    val exitCode = AddTestCommand.main(
      arrayOf(
        "org.knime.gateway.impl",
        "org.knime.gateway.impl.webui.service.GatewayDefaultServiceTests"
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

    assertEquals("Added test entry to pde.yaml\n", stdout.toString())
  }

  private fun readYamlMap(contents: String): Map<*, *> {
    val yaml = Yaml()
    return yaml.load<Any>(contents) as Map<*, *>
  }
}
