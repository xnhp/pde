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
    assertTrue(output.contains("launch"))
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
  fun `clone subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      main(arrayOf("clone", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde clone"))
  }
}
