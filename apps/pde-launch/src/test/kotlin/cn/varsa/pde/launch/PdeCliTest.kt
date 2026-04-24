package cn.varsa.pde.launch

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertTrue

class PdeCliTest {

  @Test
  fun `help is printed when no args are provided`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(emptyArray())
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde"))
    assertTrue(output.contains("Commands:"))
    assertTrue(output.contains("compile"))
  }

  @Test
  fun `help is printed with --help`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage:"))
    assertTrue(output.contains("run"))
    assertTrue(output.contains("target"))
    assertTrue(output.contains("schema"))
  }

  @Test
  fun `target command prints target subcommand help`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target"))
    assertTrue(output.contains("Commands:"))
    assertTrue(output.contains("install"))
    assertTrue(output.contains("mirror"))
  }

  @Test
  fun `target install subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "install", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target install"))
    assertTrue(output.contains("--launch=String"))
  }

  @Test
  fun `target mirror subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "mirror", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target mirror"))
    assertTrue(output.contains("--destination=String"))
  }

  @Test
  fun `launch subcommand help uses launch command name`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("launch", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde launch"))
    assertTrue(output.contains("--config=String"))
    assertTrue(output.contains("Enable JDWP for launch JVM"))
    assertTrue(output.contains("[launchPos]"))
  }

  @Test
  fun `test subcommand help uses test command name`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("test", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde test"))
    assertTrue(output.contains("--report=String"))
    assertTrue(!output.contains("--all"))
    assertTrue(!output.contains("--include"))
    assertTrue(output.contains("[testPos]"))
  }

  @Test
  fun `compile subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("compile", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde compile"))
    assertTrue(output.contains("--execute"))
  }

  @Test
  fun `api analyze subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-analyze", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde api-analyze"))
    assertTrue(output.contains("Subcommands:"))
    assertTrue(output.contains("run"))
    assertTrue(output.contains("install"))
  }

  @Test
  fun `api analyzer alias is supported`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-analyzer", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde api-analyze"))
  }

  @Test
  fun `api analyze run subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-analyze", "run", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde api-analyze"))
    assertTrue(output.contains("--fail-on-error"))
  }

  @Test
  fun `api analyze install subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-analyze", "install", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde api-analyze install"))
    assertTrue(output.contains("--baseline-root=String"))
  }

  @Test
  fun `ide-init idea subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("ide-init", "idea", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde ide-init idea"))
  }

  @Test
  fun `ide-init jdtls subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("ide-init", "jdtls", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde ide-init jdtls"))
  }

  @Test
  fun `add-test subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("add-test", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde add-test"))
  }

  @Test
  fun `add-test-helper subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("add-test-helper", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde add-test-helper"))
  }
}
