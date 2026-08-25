package cn.varsa.pde.launch

import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdeCliTest {
  @get:Rule
  val failOnJvmExit = FailOnJvmExitRule()


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
    // parent's guide column rather than plain spaces. The description column is aligned to the
    // longest command name, so only the guide prefix and description are asserted, not the padding.
    fun hasTreeLine(prefix: String, description: String) =
      lines.any { it.startsWith(prefix) && it.substring(prefix.length).trim() == description }

    assertTrue(hasTreeLine("├── target ", "Target platform commands (install, mirror, inspect)"))
    assertTrue(hasTreeLine("│   ├── install ", "Resolve/prepare target platform state"))
    assertTrue(hasTreeLine("│   ├── repair ", "Repair reusable target bundle-pool state"))
    assertTrue(hasTreeLine("│   │   ├── re-fetch ", "Re-run target install to fetch currently required artifacts"))
    assertTrue(hasTreeLine("│   └── inspect ", "Inspect target profile state and health"))
    assertTrue(hasTreeLine("│       ├── profile ", "Show profile location and bundle-pool basics"))
    assertTrue(hasTreeLine("├── ide-init ", "Generate IDE project files"))
    assertTrue(hasTreeLine("│   ├── idea ", "Generate IntelliJ project"))
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
    assertTrue(output.contains("Usage: pde target repair re-fetch"))
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
    assertTrue(output.contains("[testPos...]"))
    // picocli wraps long descriptions across lines; compare with whitespace collapsed.
    assertTrue(output.replace(Regex("\\s+"), " ").contains("defaults to all configured tests"))
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
  }

  @Test
  fun `jdt-workspace build help advertises only the options jdtBuildMain accepts`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("jdt-workspace", "build", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde jdt-workspace build"))
    assertTrue(output.contains("--data"))
    assertTrue(output.contains("--full"))
    assertTrue(output.contains("--output-root"))
    assertTrue(output.contains(".jdtls/workspace"))
    assertFalse(output.contains("--config"))
    assertFalse(output.contains("--framework"))
    assertFalse(output.contains("--json"))
    assertFalse(output.contains("--results-json"))
    assertFalse(output.contains("--bundles-info-out"))
    assertFalse(output.contains("--runtime-out"))
    assertFalse(output.contains("configPos"))
  }

  @Test
  fun `jdt-workspace init help advertises only the options workspaceSetupMain accepts`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("jdt-workspace", "init", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde jdt-workspace init"))
    assertTrue(output.contains("--config"))
    assertTrue(output.contains("--workspace"))
    assertTrue(output.contains("--output-root"))
    assertTrue(output.contains("--data-dir"))
    assertTrue(output.contains("--framework"))
    assertFalse(output.contains("--json"))
    assertFalse(output.contains("--results-json"))
    assertFalse(output.contains("--bundles-info-out"))
    assertFalse(output.contains("--runtime-out"))
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
  fun `api baseline filters add-all-from-report subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-baseline", "filters", "add-all-from-report", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde api-baseline filters add-all-from-report"))
    assertTrue(output.contains("--report=String"))
    assertTrue(output.contains("--problem=String"))
  }

  @Test
  fun `api baseline filters command prints subcommand help`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("api-baseline", "filters", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("Usage: pde api-baseline filters"))
    assertTrue(output.contains("Commands:"))
    assertTrue(output.contains("add-all-from-report"))
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
  fun `ide-init vscode subcommand is routed through pde launcher`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("ide-init", "vscode", "--help"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString()
    assertTrue(output.contains("pde ide-init vscode"))
    assertTrue(output.contains("--out=String"))
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
  fun `top-level --version prints version string`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("--version"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString().trim()
    assertTrue(output.startsWith("pde version "))
    assertTrue(output.length > "pde version ".length)
  }

  @Test
  fun `top-level -V prints version string`() {
    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    try {
      runPde(arrayOf("-V"))
    } finally {
      System.setOut(savedOut)
    }

    val output = out.toString().trim()
    assertTrue(output.startsWith("pde version "))
    assertTrue(output.length > "pde version ".length)
  }
}
