package cn.varsa.pde.launch

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse
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
    assertTrue(output.contains("pde - PDE tooling CLI"))
    assertTrue(output.contains("compile"))
    assertTrue(output.contains("Compile PDE Java bundles"))
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
    assertTrue(output.contains("pde - PDE tooling CLI"))
    assertTrue(output.contains("run"))
    assertTrue(output.contains("target"))
    assertTrue(output.contains("validate-config"))
    assertTrue(output.contains("schema"))
  }

  @Test
  fun `top-level help renders the full nested command tree`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    val lines = output.lines()

    // Nodes are prefixed with unicode box-drawing tree guides (├──/└──/│), indented under their
    // parent's guide column rather than plain spaces.
    assertTrue(lines.any { it == "├── target                 Target platform commands (install, mirror, inspect)" })
    assertTrue(lines.any { it == "│   ├── install            Resolve/prepare target platform state" })
    assertTrue(lines.any { it == "│   ├── repair             Repair reusable target bundle-pool state" })
    assertTrue(lines.any { it == "│   │   ├── re-fetch       Re-run target install to fetch currently required artifacts" })
    assertTrue(lines.any { it == "│   └── inspect            Inspect target profile state and health" })
    assertTrue(lines.any { it == "│       ├── profile        Show profile location and bundle-pool basics" })
    assertTrue(lines.any { it == "├── ide-init               Generate IDE project files" })
    assertTrue(lines.any { it == "│   ├── idea               Generate IntelliJ project" })
  }

  @Test
  fun `top-level help omits mcp tool details`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Run MCP server over stdin/stdout exposing PDE workflow tools."))
    assertFalse(output.contains("pde mcp tools list"))
    assertFalse(output.contains("MCP tools for pde"))
    assertFalse(output.contains("pde_compile_workspace"))
  }

  @Test
  fun `mcp tools list prints mcp tool details`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("mcp", "tools", "list"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("MCP tools for pde"))
    assertTrue(output.contains("pde_compile_workspace"))
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
    assertTrue(output.contains("health"))
    assertTrue(output.contains("repair"))
    assertTrue(output.contains("inspect"))
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
    assertTrue(output.contains("--copy-path"))
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
  fun `target inspect command prints inspect subcommand help`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "inspect", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target inspect"))
    assertTrue(output.contains("profile"))
    assertTrue(output.contains("ius"))
    assertTrue(output.contains("diff"))
    assertTrue(output.contains("health"))
    assertTrue(output.contains("snapshots"))
  }

  @Test
  fun `target inspect profile subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "inspect", "profile", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target inspect profile"))
    assertTrue(output.contains("--json"))
  }

  @Test
  fun `target inspect ius subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "inspect", "ius", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target inspect ius"))
    assertTrue(output.contains("--list"))
  }

  @Test
  fun `target inspect health subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "inspect", "health", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target inspect health"))
    assertTrue(output.contains("--limit=Int"))
  }

  @Test
  fun `target health subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "health", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target health"))
    assertTrue(output.contains("--limit=Int"))
  }

  @Test
  fun `target repair subcommands are routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("target", "repair", "--help"))
      runPde(arrayOf("target", "repair", "re-fetch", "--help"))
      runPde(arrayOf("target", "repair", "quarantine", "--help"))
      runPde(arrayOf("target", "repair", "rebuild-index", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde target repair"))
    assertTrue(output.contains("Usage: pde target install"))
    assertTrue(output.contains("Usage: pde target repair quarantine"))
    assertTrue(output.contains("Usage: pde target repair rebuild-index"))
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
    assertTrue(!output.contains("--workspace"))
    assertTrue(!output.contains("--target-root"))
    assertTrue(!output.contains("--dev-prop"))
    assertTrue(!output.contains("--product"))
    assertTrue(!output.contains("--application"))
    assertTrue(!output.contains("--splash"))
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
    assertTrue(output.contains("defaults to all configured tests"))
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
    assertTrue(!output.contains("--execute"))
    assertTrue(!output.contains("--workspace"))
    assertTrue(output.contains("--full-rebuild"))
  }

  @Test
  fun `validate config subcommand help uses command name`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("validate-config", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde validate-config"))
    assertTrue(output.contains("file"))
    assertTrue(output.contains("YAML config file to validate"))
  }

  @Test
  fun `api baseline subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-baseline", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde api-baseline"))
    assertTrue(output.contains("Commands:"))
    assertTrue(output.contains("check"))
  }

  @Test
  fun `api baseline check subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-baseline", "check", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde api-baseline check"))
    assertTrue(output.contains("--baseline-root=String"))
    assertTrue(output.contains("--bundle=String"))
    assertTrue(output.contains("--report=String"))
    assertTrue(output.contains("--legacy"))
    assertTrue(!output.contains("--application"))
  }

  @Test
  fun `api filters add-from-report subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-filters", "add-from-report", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde api-filters add-from-report"))
    assertTrue(output.contains("--report=String"))
    assertTrue(output.contains("--problem=String"))
  }

  @Test
  fun `api filters command prints subcommand help`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-filters", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde api-filters"))
    assertTrue(output.contains("Commands:"))
    assertTrue(output.contains("add-from-report"))
  }

  @Test
  fun `target install subcommand accepts api-baseline mode`() {
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
    assertTrue(output.contains("--baseline-root=String"))
    assertTrue(output.contains("api-baseline"))
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
}
