package cn.varsa.pde.launch

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidateConfigCommandTest {
  @Test
  fun `validate config succeeds for valid config`() {
    val tempDir = Files.createTempDirectory("pde-validate-config")
    val config = tempDir.resolve("pde.yaml")
    Files.writeString(
      config,
      """
      bundles:
        - path: ./org.example.bundle
      launches:
        - name: app
          application: org.example.app
      """.trimIndent()
    )

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    val exitCode = try {
      runPde(arrayOf("validate-config", config.toString()))
    } finally {
      System.setOut(savedOut)
    }

    assertEquals(0, exitCode)
    assertEquals("Config is valid: ${config.toAbsolutePath().normalize()}\n", out.toString())
  }

  @Test
  fun `validate config prints schema violations for invalid config`() {
    val tempDir = Files.createTempDirectory("pde-validate-config")
    val config = tempDir.resolve("pde.yaml")
    Files.writeString(
      config,
      """
      bundles:
        - classRoots: [bin]
      launches:
        - product: org.example.product
      unknown: true
      """.trimIndent()
    )

    val err = ByteArrayOutputStream()
    val savedErr = System.err
    System.setErr(PrintStream(err))
    val exitCode = try {
      runPde(arrayOf("validate-config", config.toString()))
    } finally {
      System.setErr(savedErr)
    }

    assertEquals(1, exitCode)
    val output = err.toString()
    assertTrue(output.contains("Invalid config ${config.toAbsolutePath().normalize()}:"), output)
    assertTrue(output.contains("$.bundles[0]"), output)
    assertTrue(output.contains("$.launches[0]"), output)
    assertTrue(output.contains("unknown"), output)
  }

  @Test
  fun `validate config requires one file argument`() {
    val err = ByteArrayOutputStream()
    val savedErr = System.err
    System.setErr(PrintStream(err))
    val exitCode = try {
      runPde(arrayOf("validate-config"))
    } finally {
      System.setErr(savedErr)
    }

    assertEquals(1, exitCode)
    assertEquals("Usage: pde validate-config <file>\n", err.toString())
  }
}
