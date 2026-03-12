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
      main(emptyArray())
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
      main(arrayOf("--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage:"))
    assertTrue(output.contains("run"))
    assertTrue(output.contains("target"))
  }

  @Test
  fun `target command prints target subcommand help`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("target", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target"))
    assertTrue(output.contains("install"))
    assertTrue(output.contains("mirror"))
  }

  @Test
  fun `target install subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("target", "install", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target"))
  }

  @Test
  fun `target mirror subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("target", "mirror", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target"))
  }

  @Test
  fun `compile subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("compile", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde compile"))
  }

  @Test
  fun `worktrees-init subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("worktrees-init", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde worktrees-init"))
  }

  @Test
  fun `fetch_jars subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("fetch_jars", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde fetch_jars"))
  }

  @Test
  fun `codegen subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("codegen", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde codegen"))
  }

  @Test
  fun `foreach-repo subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("foreach-repo", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde foreach-repo"))
  }

  @Test
  fun `add-test subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("add-test", "--help"))
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
      main(arrayOf("add-test-helper", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde add-test-helper"))
  }
}
