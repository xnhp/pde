package cn.varsa.pde.launch

import cn.varsa.cli.core.CliArgs
import cn.varsa.cli.core.CliCommandGroup
import cn.varsa.cli.core.CliCommandLeaf
import cn.varsa.cli.core.CliMain
import cn.varsa.cli.core.CliMcpRegistrationConfig
import cn.varsa.cli.core.CliOption
import cn.varsa.cli.core.CliPositionalArg
import cn.varsa.cli.core.cliMcpToolsListText
import cn.varsa.pde.resolver.cli.compileMain
import cn.varsa.pde.resolver.cli.launchMain
import cn.varsa.pde.resolver.cli.workspaceSetupMain
import cn.varsa.pde.resolver.cli.jdtBuildMain
import pde.format.main as formatMain
import picocli.CommandLine
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlin.system.exitProcess

private fun forwardToLaunch(commandName: String, vararg prefix: String): (Array<String>) -> Int = { args ->
  launchMain((prefix.toList() + args).toTypedArray(), commandName = commandName)
  0
}

private val launchPositionals = listOf(
  CliPositionalArg(0, "configPos", "YAML launch configuration (positional)", "0..1"),
  CliPositionalArg(1, "launchPos", "Launch name (optional, from launches entry)", "0..1")
)

private val launchOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration (supports launches/tests)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log-level"), "Logging level (error|warn|info|debug|trace)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log"), "Write launched PDE process stdout/stderr to a file", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--verbose", "-v"), "Enable INFO logging"),
  CliOption(listOf("--debug"), "Enable JDWP for launch JVM"),
  CliOption(listOf("--osgiDebug"), "Enable OSGi debug output (-debug)"),
  CliOption(listOf("--clean"), "Launch with Eclipse -clean and rebuild OSGi framework state"),
  CliOption(listOf("--dry-run"), "Parse configuration only"),
  CliOption(listOf("--framework"), "Framework BSN", takesValue = true, valueLabel = "String", defaultValue = "org.eclipse.osgi"),
  CliOption(listOf("--output", "-o"), "Output directory for config.ini/bundles.info/dev.properties", takesValue = true, valueLabel = "String")
)

private val testPositionals = listOf(
  CliPositionalArg(0, "testPos", "Test name/index (optional, repeatable; defaults to all configured tests)", "0..*")
)

private val testOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log-level"), "Logging level (error|warn|info|debug|trace)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log"), "Write application stdout/stderr to log file", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--verbose", "-v"), "Enable INFO logging"),
  CliOption(listOf("--debug"), "Enable DEBUG logging"),
  CliOption(listOf("--osgiDebug"), "Enable OSGi debug output (-debug)"),
  CliOption(listOf("--clean"), "Launch with Eclipse -clean and rebuild OSGi framework state"),
  CliOption(listOf("--listen-host"), "Host to bind", takesValue = true, valueLabel = "String", defaultValue = "127.0.0.1"),
  CliOption(listOf("--listen-port"), "Fixed port to bind", takesValue = true, valueLabel = "Int"),
  CliOption(listOf("--port-range"), "Inclusive port range start-end", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--timeout"), "Seconds to wait for PDE connection", takesValue = true, valueLabel = "Int", defaultValue = "180"),
  CliOption(listOf("--report"), "Reporting sink (teamcity, junit-xml:/path)", takesValue = true, valueLabel = "String", arity = "1", repeatable = true),
  CliOption(listOf("--forward-log"), "Prefix and stream an existing log source (label=path)", takesValue = true, valueLabel = "String", arity = "1", repeatable = true),
  CliOption(listOf("--quiet"), "Suppress console test logs"),
)

private val targetInstallPositionals = listOf(
  CliPositionalArg(0, "configPos", "YAML launch configuration (positional)", "0..1"),
  CliPositionalArg(1, "modePos", "Install mode: pass 'api-baseline' to provision the API baseline profile instead of the primary target", "0..1")
)

private val targetInstallOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--launch"), "Installer launch name (defaults to 'install' if present)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log-level"), "Logging level (error|warn|info|debug|trace)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log"), "Write application stdout/stderr to log file", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--copy-path"), "Copy installed profile path to the clipboard"),
  CliOption(listOf("--verbose", "-v"), "Enable INFO logging"),
  CliOption(listOf("--debug"), "Enable DEBUG logging"),
  CliOption(listOf("--baseline-root"), "Baseline source for 'api-baseline' install mode (target root, profile path, or .target file; defaults from target config)", takesValue = true, valueLabel = "String")
)

private val targetMirrorPositionals = listOf(
  CliPositionalArg(0, "configPos", "YAML launch configuration (positional)", "0..1")
)

private val targetMirrorOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--destination", "-d"), "Destination repository path or URI", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--write-mode"), "Write mode (clean)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--metadata-only"), "Mirror metadata only"),
  CliOption(listOf("--artifacts-only"), "Mirror artifacts only"),
  CliOption(listOf("--log-level"), "Logging level (error|warn|info|debug|trace)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log"), "Write application stdout/stderr to log file", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--verbose", "-v"), "Enable INFO logging"),
  CliOption(listOf("--debug"), "Enable DEBUG logging")
)

private val targetInspectPositionals = listOf(
  CliPositionalArg(0, "configPos", "YAML launch configuration (positional)", "0..1")
)

private val targetInspectProfileOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--json"), "Emit JSON output")
)

private val targetInspectIusOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--json"), "Emit JSON output"),
  CliOption(listOf("--list"), "Print bundle names only, one per line"),
  CliOption(listOf("--limit"), "Maximum number of IUs to print", takesValue = true, valueLabel = "Int", defaultValue = "200")
)

private val targetInspectDiffOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--json"), "Emit JSON output")
)

private val targetInspectHealthOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--json"), "Emit JSON output"),
  CliOption(listOf("--limit"), "Maximum number of health issues to print", takesValue = true, valueLabel = "Int", defaultValue = "100")
)

private val targetHealthOptions = targetInspectHealthOptions

private val targetRepairOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--dry-run"), "Print repair actions without changing files")
)

private val targetRepairRefetchOptions = targetInstallOptions

private val targetRepairPositionals = listOf(
  CliPositionalArg(0, "configPos", "YAML launch configuration (positional)", "0..1")
)

private val targetInspectSnapshotsOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--json"), "Emit JSON output")
)

private val compilePositionals = listOf(
  CliPositionalArg(0, "configPos", "YAML launch configuration (positional)", "0..1")
)

private val compileOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--framework"), "Framework BSN", takesValue = true, valueLabel = "String", defaultValue = "org.eclipse.osgi"),
  CliOption(listOf("--json"), "Emit compile specs as JSON"),
  CliOption(listOf("--debug"), "Emit debug info (lines/vars/source)"),
  CliOption(listOf("--results-json"), "Write compile results to JSON file", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--output-root"), "Override workspace bundle output dir (relative to module root, e.g., bin)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--bundles-info-out"), "Write bundles.info reflecting compiled workspace outputs", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--runtime-out"), "Write config.ini/dev.properties/bundles.info for compiled outputs under this directory", takesValue = true, valueLabel = "String")
)

private val apiBaselineCheckPositionals = listOf(
  CliPositionalArg(0, "configPos", "Launch config YAML (auto-discovered if absent)", "0..1")
)

private val apiBaselineCheckOptions = listOf(
  CliOption(listOf("--config"), "Path to launch config YAML (auto-discovered from current directory if absent)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log-level"), "Log level (trace, debug, info, warn, error)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log"), "Write the analyzer launcher output log (one shared log for the whole batch invocation)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--verbose", "-v"), "Enable verbose logging"),
  CliOption(listOf("--debug"), "Enable debug logging"),
  CliOption(listOf("--baseline-root"), "Baseline source (target root, profile path, or .target file; defaults from target config)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--bundle"), "Analyze only the named workspace bundle BSN (repeatable; default: all workspace bundles)", takesValue = true, valueLabel = "String", arity = "1", repeatable = true),
  CliOption(listOf("--workspace-data"), "Path to workspace data directory from 'pde jdt-workspace init'; auto-detected at .jdtls/workspace/data, fails if absent (use --legacy to skip)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--legacy"), "Skip workspace-data / @since-tag detection; run the legacy binary-only analysis path"),
  CliOption(listOf("--report"), "Write JSON problem report; with multiple bundles, written per-bundle to .api-baseline/reports/ (consumed by add-all-from-report / add-filter)", takesValue = true, valueLabel = "String")
)

private val apiBaselineAddAllFromReportPositionals = listOf(
  CliPositionalArg(0, "reportPos", "Path to a report JSON; auto-inferred from .api-baseline/reports/ when absent", "0..1")
)

private val apiBaselineAddAllFromReportOptions = listOf(
  CliOption(listOf("--report"), "Path to a report JSON from 'pde api-baseline check'; auto-inferred from .api-baseline/reports/ when absent", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--problem"), "Select a specific problemRef (repeatable; use --all to select everything)", takesValue = true, valueLabel = "String", arity = "1", repeatable = true),
  CliOption(listOf("--all"), "Select all problems; can still be narrowed with --bundle, --category, --severity"),
  CliOption(listOf("--bundle"), "Narrow selection to specific bundle BSNs (repeatable)", takesValue = true, valueLabel = "String", arity = "1", repeatable = true),
  CliOption(listOf("--category"), "Narrow selection to specific problem categories (repeatable, case-insensitive)", takesValue = true, valueLabel = "String", arity = "1", repeatable = true),
  CliOption(listOf("--severity"), "Narrow selection to specific severities (repeatable, case-insensitive)", takesValue = true, valueLabel = "String", arity = "1", repeatable = true),
  CliOption(listOf("--comment-template"), "Filter comment with {problemRef}, {bundleBsn}, {timestamp} placeholders", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--dry-run"), "Preview .api_filters changes without writing files (default)"),
  CliOption(listOf("--apply"), "Write .settings/.api_filters changes to disk (default: dry-run preview only)"),
  CliOption(listOf("--allow-empty-selection"), "Exit 0 when selection yields no problems (default: exit 3)")
)

private val apiBaselineAddFilterPositionals = listOf(
  CliPositionalArg(0, "id", "problemRef from the report (e.g. P000001); always writes the filter", "1")
)

private val apiBaselineAddFilterOptions = listOf(
  CliOption(listOf("--report"), "Path to a report JSON from 'pde api-baseline check'; auto-inferred from .api-baseline/reports/ when absent", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--comment-template"), "Filter comment with {problemRef}, {bundleBsn}, {timestamp} placeholders", takesValue = true, valueLabel = "String")
)

private val validateConfigPositionals = listOf(
  CliPositionalArg(0, "file", "YAML config file to validate", "1")
)

internal val pdeCommand = CliCommandGroup(
  name = "pde",
  description = "PDE tooling CLI",
  children = listOf(
    CliCommandGroup(
      name = "ide-init",
      description = "Generate IDE project files",
      children = listOf(
        CliCommandLeaf(
          name = "idea",
          description = "Generate IntelliJ project",
          handler = { args -> IjInit.main(args) }
        ),
        CliCommandLeaf(
          name = "vscode",
          description = "Generate VS Code multi-root workspace (run 'pde lsp init' for JDT LS project files)",
          handler = { args -> VscodeInit.main(args) }
        )
      )
    ),
    CliCommandGroup(
      name = "lsp",
      description = "Java Language Server (JDT LS) integration",
      children = listOf(
        CliCommandLeaf(
          name = "init",
          description = "Generate JDT LS project files (.project/.classpath) for this workspace",
          handler = { args -> JdtlsInitCommand.main(args) }
        ),
        CliCommandLeaf(
          name = "run",
          description = "Generate metadata and launch JDT LS for this workspace",
          handler = { args -> LspRunCommand.main(args) }
        )
      )
    ),
    CliCommandLeaf(
      name = "compile",
      description = "Compile PDE Java bundles",
      handler = { args -> compileMain(args) },
      mixinStandardHelpOptions = true,
      options = compileOptions,
      positionalArgs = compilePositionals
    ),
    CliCommandGroup(
      name = "jdt-workspace",
      description = "Eclipse workspace management",
      children = listOf(
        CliCommandLeaf(
          name = "init",
          description = "Create Eclipse IProject/IJavaProject from resolver output",
          handler = { args -> workspaceSetupMain(args) },
          mixinStandardHelpOptions = true,
          options = compileOptions,
          positionalArgs = compilePositionals
        ),
        CliCommandLeaf(
          name = "build",
          description = "Run incremental JDT compilation in an Equinox workspace",
          handler = { args -> jdtBuildMain(args) },
          mixinStandardHelpOptions = true,
          options = compileOptions,
          positionalArgs = compilePositionals
        )
      )
    ),
    CliCommandLeaf(
      name = "format",
      description = "Format Java sources via Eclipse formatter",
      handler = { args ->
        formatMain(args)
        0
      }
    ),
    CliCommandLeaf(
      name = "add-test",
      description = "Append a test entry to launch config",
      handler = { args -> AddTestCommand.main(args) }
    ),
    CliCommandLeaf(
      name = "run",
      description = "Run a launch config (alias of launch)",
      handler = forwardToLaunch("pde run"),
      mixinStandardHelpOptions = true,
      options = launchOptions,
      positionalArgs = launchPositionals
    ),
    CliCommandLeaf(
      name = "launch",
      description = "Run a launch config (alias of run)",
      handler = forwardToLaunch("pde launch"),
      mixinStandardHelpOptions = true,
      options = launchOptions,
      positionalArgs = launchPositionals
    ),
    CliCommandGroup(
      name = "target",
      description = "Target platform commands (install, mirror, inspect)",
      children = listOf(
        CliCommandLeaf(
          name = "install",
          description = "Resolve/prepare target platform state",
          handler = forwardToLaunch("pde target install", "target", "install"),
          mixinStandardHelpOptions = true,
          options = targetInstallOptions,
          positionalArgs = targetInstallPositionals
        ),
        CliCommandLeaf(
          name = "mirror",
          description = "Mirror update sites from a .target definition",
          handler = forwardToLaunch("pde target mirror", "target", "mirror"),
          mixinStandardHelpOptions = true,
          options = targetMirrorOptions,
          positionalArgs = targetMirrorPositionals
        ),
        CliCommandLeaf(
          name = "health",
          description = "Run consistency checks for the configured bundle pool",
          handler = forwardToLaunch("pde target health", "target", "health"),
          mixinStandardHelpOptions = true,
          options = targetHealthOptions,
          positionalArgs = targetInspectPositionals
        ),
        CliCommandGroup(
          name = "repair",
          description = "Repair reusable target bundle-pool state",
          children = listOf(
            CliCommandLeaf(
              name = "re-fetch",
              description = "Re-run target install to fetch currently required artifacts",
              handler = forwardToLaunch("pde target repair re-fetch", "target", "repair", "re-fetch"),
              mixinStandardHelpOptions = true,
              options = targetRepairRefetchOptions,
              positionalArgs = targetRepairPositionals
            ),
            CliCommandLeaf(
              name = "quarantine",
              description = "Move cached features that pin missing bundles out of the pool",
              handler = forwardToLaunch("pde target repair quarantine", "target", "repair", "quarantine"),
              mixinStandardHelpOptions = true,
              options = targetRepairOptions,
              positionalArgs = targetRepairPositionals
            ),
            CliCommandLeaf(
              name = "rebuild-index",
              description = "Rebuild bundle-pool artifacts.xml from physical files",
              handler = forwardToLaunch("pde target repair rebuild-index", "target", "repair", "rebuild-index"),
              mixinStandardHelpOptions = true,
              options = targetRepairOptions,
              positionalArgs = targetRepairPositionals
            )
          )
        ),
        CliCommandGroup(
          name = "inspect",
          description = "Inspect target profile state and health",
          children = listOf(
            CliCommandLeaf(
              name = "profile",
              description = "Show profile location and bundle-pool basics",
              handler = forwardToLaunch("pde target inspect profile", "target", "inspect", "profile"),
              mixinStandardHelpOptions = true,
              options = targetInspectProfileOptions,
              positionalArgs = targetInspectPositionals
            ),
            CliCommandLeaf(
              name = "ius",
              description = "List installable units from latest profile snapshot",
              handler = forwardToLaunch("pde target inspect ius", "target", "inspect", "ius"),
              mixinStandardHelpOptions = true,
              options = targetInspectIusOptions,
              positionalArgs = targetInspectPositionals
            ),
            CliCommandLeaf(
              name = "diff",
              description = "Diff auto-selected latest/previous snapshots (use `inspect snapshots` to view)",
              handler = forwardToLaunch("pde target inspect diff", "target", "inspect", "diff"),
              mixinStandardHelpOptions = true,
              options = targetInspectDiffOptions,
              positionalArgs = targetInspectPositionals
            ),
            CliCommandLeaf(
              name = "health",
              description = "Run consistency checks for profile and bundle pool",
              handler = forwardToLaunch("pde target inspect health", "target", "inspect", "health"),
              mixinStandardHelpOptions = true,
              options = targetInspectHealthOptions,
              positionalArgs = targetInspectPositionals
            ),
            CliCommandLeaf(
              name = "snapshots",
              description = "List available profile snapshots",
              handler = forwardToLaunch("pde target inspect snapshots", "target", "inspect", "snapshots"),
              mixinStandardHelpOptions = true,
              options = targetInspectSnapshotsOptions,
              positionalArgs = targetInspectPositionals
            )
          )
        )
      )
    ),
    CliCommandLeaf(
      name = "test",
      description = "Run PDE test launch",
      handler = forwardToLaunch("pde test", "test"),
      mixinStandardHelpOptions = true,
      options = testOptions,
      positionalArgs = testPositionals
    ),
    CliCommandGroup(
      name = "api-baseline",
      description = "API baseline compatibility analysis and filter management",
      children = listOf(
        CliCommandLeaf(
          name = "check",
          description = "Run API compatibility analysis against the baseline target",
          handler = forwardToLaunch("pde api-baseline check", "api-baseline", "check"),
          mixinStandardHelpOptions = true,
          options = apiBaselineCheckOptions,
          positionalArgs = apiBaselineCheckPositionals
        ),
        CliCommandLeaf(
          name = "add-all-from-report",
          description = "Add .api_filters entries for problems in api-baseline check report(s) (--apply to write)",
          handler = forwardToLaunch("pde api-baseline add-all-from-report", "api-baseline", "add-all-from-report"),
          mixinStandardHelpOptions = true,
          options = apiBaselineAddAllFromReportOptions,
          positionalArgs = apiBaselineAddAllFromReportPositionals
        ),
        CliCommandLeaf(
          name = "add-filter",
          description = "Add a .api_filters entry for a specific problem reference (always writes)",
          handler = forwardToLaunch("pde api-baseline add-filter", "api-baseline", "add-filter"),
          mixinStandardHelpOptions = true,
          options = apiBaselineAddFilterOptions,
          positionalArgs = apiBaselineAddFilterPositionals
        )
      )
    ),
    CliCommandLeaf(
      name = "validate-config",
      description = "Validate a pde YAML config against the schema",
      handler = { args -> ValidateConfigCommand.main(args) },
      mixinStandardHelpOptions = true,
      positionalArgs = validateConfigPositionals
    ),
    CliCommandLeaf(
      name = "schema",
      description = "print the full path to the config file schema",
      handler = { args -> SchemaCommand.main(args) }
    ),
    CliCommandGroup(
      name = "mcp",
      description = pdeMcpHelpText(),
      handler = {
        runPdeMcpServer()
        0
      },
      mixinStandardHelpOptions = true,
      children = listOf(
        CliCommandGroup(
          name = "tools",
          description = "Inspect PDE MCP tools",
          children = listOf(
            CliCommandLeaf(
              name = "list",
              description = "List implemented MCP tools and parameters",
              handler = { args ->
                CliArgs.requireArgCount(args, 0, "pde mcp tools list")
                println(pdeMcpWorkflowCommand.cliMcpToolsListText())
                0
              },
              mixinStandardHelpOptions = true
            )
          )
        )
      )
    )
  )
)

private fun pdeMcpHelpText(): String = """
  Run MCP server over stdin/stdout exposing PDE workflow tools.

  Use `pde mcp tools list` to inspect available MCP tools and their parameters.

  Opencode example:
  {
    "mcp": {
      "pde": {
        "type": "local",
        "command": ["/path/to/pde", "mcp"],
        "cwd": "/path/to/workspace",
        "enabled": true
      }
    }
  }
""".trimIndent()

private val topLevelHelpArgs = setOf("--help", "-h")

internal fun runPde(args: Array<String>): Int {
  if (args.isEmpty() || (args.size == 1 && args[0] in topLevelHelpArgs)) {
    println(pdeCommandTreeHelpText(pdeCommand))
    return 0
  }
  return CliMain.run(createPdeCommandLine(), args)
}

fun main(args: Array<String>) {
  exitProcess(runPde(args))
}

internal fun createPdeCommandLine(): CommandLine = CliMain.createCommandLine(pdeCommand)

fun Server.registerPdeTools(config: CliMcpRegistrationConfig = CliMcpRegistrationConfig()) {
  this.registerPdeWorkflowTools(config)
}
