package cn.varsa.pde.launch

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
}
