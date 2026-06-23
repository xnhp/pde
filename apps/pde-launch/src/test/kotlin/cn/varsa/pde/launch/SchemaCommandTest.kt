package cn.varsa.pde.launch

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaCommandTest {
  @Test
  fun `schema subcommand prints non-temp schema path`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    val exitCode = try {
      runPde(arrayOf("schema"))
      0
    } finally {
      System.setOut(savedOut)
    }

    assertEquals(0, exitCode)
    val schemaPath = out.toString().trim()
    assertTrue(schemaPath.endsWith("pde.schema.yaml"), "Expected schema filename in output, got: $schemaPath")
    assertTrue(!schemaPath.startsWith("/tmp"), "Schema path should not be temp path: $schemaPath")
  }

  @Test
  fun `schema uses boolean additionalProperties for sap compatibility`() {
    val schemaPath = schemaPathFromCommand()
    val lines = Files.readAllLines(schemaPath)

    lines.forEachIndexed { index, line ->
      if (line.trim() == "additionalProperties:") {
        val nextNonBlank = lines.drop(index + 1).firstOrNull { it.isNotBlank() }
        assertTrue(
          nextNonBlank?.trim() == "true" || nextNonBlank?.trim() == "false",
          "Expected boolean additionalProperties near line ${index + 1}, got: $nextNonBlank"
        )
      }
    }
  }

  private fun schemaPathFromCommand(): Path {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("schema"))
    } finally {
      System.setOut(savedOut)
    }
    return Path.of(out.toString().trim())
  }
}
