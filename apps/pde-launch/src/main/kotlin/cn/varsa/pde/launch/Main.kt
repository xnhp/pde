package cn.varsa.pde.launch

import cn.varsa.cli.core.CliCommandGroup
import cn.varsa.cli.core.CliCommandLeaf
import cn.varsa.cli.core.CliMain
import cn.varsa.cli.core.CliOption
import cn.varsa.cli.core.CliPositionalArg
import cn.varsa.pde.resolver.cli.compileMain
import cn.varsa.pde.resolver.cli.launchMain
import pde.format.main as formatMain
import picocli.CommandLine
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
  CliOption(listOf("--log"), "Write application stdout/stderr to log file", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--verbose", "-v"), "Enable INFO logging"),
  CliOption(listOf("--debug"), "Enable JDWP for launch JVM"),
  CliOption(listOf("--osgiDebug"), "Enable OSGi debug output (-debug)"),
  CliOption(listOf("--dry-run"), "Parse configuration only"),
  CliOption(listOf("--target-root", "-t"), "Target root (repeatable)", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--workspace", "-w"), "Workspace bundle directory (repeatable)", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--dev-prop"), "Dev properties entry in form bsn=path1,path2", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--product"), "Product identifier", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--application"), "Application identifier", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--splash"), "Splash bundle symbolic name", takesValue = true, valueLabel = "String"),
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
  CliOption(listOf("--listen-host"), "Host to bind", takesValue = true, valueLabel = "String", defaultValue = "127.0.0.1"),
  CliOption(listOf("--listen-port"), "Fixed port to bind", takesValue = true, valueLabel = "Int"),
  CliOption(listOf("--port-range"), "Inclusive port range start-end", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--timeout"), "Seconds to wait for PDE connection", takesValue = true, valueLabel = "Int", defaultValue = "180"),
  CliOption(listOf("--report"), "Reporting sink (teamcity, junit-xml:/path)", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--forward-log"), "Forward log in form label=path", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--quiet"), "Suppress console test logs"),
)

private val targetInstallPositionals = listOf(
  CliPositionalArg(0, "configPos", "YAML launch configuration (positional)", "0..1")
)

private val targetInstallOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--launch"), "Installer launch name (defaults to 'install' if present)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log-level"), "Logging level (error|warn|info|debug|trace)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log"), "Write application stdout/stderr to log file", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--verbose", "-v"), "Enable INFO logging"),
  CliOption(listOf("--debug"), "Enable DEBUG logging")
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
  CliOption(listOf("--limit"), "Maximum number of IUs to print", takesValue = true, valueLabel = "Int", defaultValue = "200")
)

private val targetInspectDiffOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--json"), "Emit JSON output")
)

private val targetInspectHealthOptions = listOf(
  CliOption(listOf("--config"), "YAML launch configuration", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--json"), "Emit JSON output"),
  CliOption(listOf("--limit"), "Maximum number of missing artifacts to print", takesValue = true, valueLabel = "Int", defaultValue = "100")
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
  CliOption(listOf("--workspace", "-w"), "Workspace bundle directory (repeatable)", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--framework"), "Framework BSN", takesValue = true, valueLabel = "String", defaultValue = "org.eclipse.osgi"),
  CliOption(listOf("--json"), "Emit compile specs as JSON"),
  CliOption(listOf("--execute"), "Run ECJ compilation (default when using a launch config)"),
  CliOption(listOf("--full-rebuild"), "Force full rebuild of all workspace bundles (skip incremental cache)"),
  CliOption(listOf("--debug"), "Emit debug info (lines/vars/source)"),
  CliOption(listOf("--results-json"), "Write compile results (when --execute) to JSON file", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--output-root"), "Override workspace bundle output dir (relative to module root, e.g., bin)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--bundles-info-out"), "Write bundles.info reflecting compiled workspace outputs", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--runtime-out"), "Write config.ini/dev.properties/bundles.info for compiled outputs under this directory", takesValue = true, valueLabel = "String")
)

private val apiAnalyzePositionals = listOf(
  CliPositionalArg(0, "configPos", "Launch config YAML (defaults to discovered pde.yaml)", "0..1")
)

private val apiAnalyzeOptions = listOf(
  CliOption(listOf("--config"), "Path to launch config YAML (defaults to discovered pde.yaml)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log-level"), "Log level (trace, debug, info, warn, error)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--log"), "Write analyzer launcher output log (per-bundle suffixes when analyzing multiple bundles)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--verbose", "-v"), "Enable verbose logging"),
  CliOption(listOf("--debug"), "Enable debug logging"),
  CliOption(listOf("--baseline-root"), "Baseline source (target root, profile path, or .target file; defaults from target config)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--dependency-list"), "Write resolved dependency list text file (default: <config-dir>/dependencies-list.txt)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--baseline-list"), "Write baseline list text file (default: api-analyzer/baseline-list.txt)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--jdt-compliance"), "Override JDT compliance (uses temp project copy)", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--application"), "API analyzer application id", takesValue = true, valueLabel = "String", defaultValue = "com.knime.enterprise.devops.eclipse.ApiAnalyzer"),
  CliOption(listOf("--fail-on-error"), "Fail when API errors are detected"),
  CliOption(listOf("--report"), "Write JSON problem report (schemaVersion/problemRef/problemId/messageArgs/...) for downstream tools", takesValue = true, valueLabel = "String")
)

private val apiFiltersAddFromReportPositionals = listOf(
  CliPositionalArg(0, "reportPos", "Path to api-analyze --report JSON", "0..1")
)

private val apiFiltersAddFromReportOptions = listOf(
  CliOption(listOf("--report"), "Path to api-analyze --report JSON", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--problem"), "Select a problemRef from the report (repeatable)", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--all"), "Select all report problems (can still be narrowed by filters)"),
  CliOption(listOf("--bundle"), "Keep only selected bundle BSNs (repeatable)", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--category"), "Keep only selected categories (repeatable)", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--severity"), "Keep only selected severities (repeatable)", takesValue = true, valueLabel = "String", arity = "1"),
  CliOption(listOf("--comment-template"), "Set filter comment with {problemRef},{bundleBsn},{timestamp}", takesValue = true, valueLabel = "String"),
  CliOption(listOf("--dry-run"), "Preview .api_filters changes without writing files (default mode)"),
  CliOption(listOf("--apply"), "Write .settings/.api_filters changes to disk"),
  CliOption(listOf("--allow-empty-selection"), "Return success when selection yields no problems")
)

private val pdeCommand = CliCommandGroup(
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
          name = "jdtls",
          description = "Generate .project/.classpath for JDT LS",
          handler = { args -> JdtlsInitCommand.main(args) }
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
      name = "add-test-helper",
      description = "Append a gateway helper test entry",
      handler = { args -> AddTestHelperCommand.main(args) }
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
    CliCommandLeaf(
      name = "api-analyze",
      description = "Run API analysis",
      handler = forwardToLaunch("pde api-analyze", "api-analyze"),
      aliases = listOf("api-analyzer"),
      mixinStandardHelpOptions = true,
      options = apiAnalyzeOptions,
      positionalArgs = apiAnalyzePositionals
    ),
    CliCommandGroup(
      name = "api-filters",
      description = "Manage API filters from analyzer reports",
      children = listOf(
        CliCommandLeaf(
          name = "add-from-report",
          description = "Add .api_filters entries from api-analyze report JSON",
          handler = forwardToLaunch("pde api-filters add-from-report", "api-filters", "add-from-report"),
          mixinStandardHelpOptions = true,
          options = apiFiltersAddFromReportOptions,
          positionalArgs = apiFiltersAddFromReportPositionals
        )
      )
    ),
    CliCommandLeaf(
      name = "schema",
      description = "Print the active pde schema path",
      handler = { args -> SchemaCommand.main(args) }
    )
  )
)

internal fun runPde(args: Array<String>): Int = createPdeCommandLine().execute(*args)

fun main(args: Array<String>) {
  exitProcess(runPde(args))
}

internal fun createPdeCommandLine(): CommandLine = CliMain.createCommandLine(pdeCommand)
