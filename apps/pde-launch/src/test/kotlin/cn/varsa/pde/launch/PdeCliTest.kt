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
    assertTrue(output.contains("pde - PDE tooling CLI"))
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
  fun `issue-new subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("issue-new", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde issue-new"))
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
}
