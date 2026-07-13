package cn.varsa.pde.launch

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.optional
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object LspRunCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde lsp run")
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    val issueDirOpt by parser.option(
      ArgType.String,
      fullName = "issue-dir",
      description = "Issue directory containing pde.yaml and repos"
    )
    val dataDirOpt by parser.option(
      ArgType.String,
      fullName = "data-dir",
      description = "JDT LS workspace data directory (defaults to <issue-dir>/.lsp)"
    )
    val jdtlsHomeOpt by parser.option(
      ArgType.String,
      fullName = "jdtls-home",
      description = "Path to an existing JDT LS install (skips download/cache)"
    )
    val downloadOpt by parser.option(
      ArgType.Boolean,
      fullName = "download",
      description = "Download and cache a JDT LS distribution if none is cached yet"
    ).default(false)
    val configPos by parser.argument(
      ArgType.String,
      description = "YAML launch configuration (positional)"
    ).optional()
    parser.parse(args)

    val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
    val issueDir = issueDirOpt?.let { Paths.get(it).toAbsolutePath().normalize() } ?: cwd

    val initArgs = mutableListOf<String>()
    configOpt?.let { initArgs += listOf("--config", it) }
    configPos?.let { initArgs += it }
    issueDirOpt?.let { initArgs += listOf("--issue-dir", it) }

    // JdtlsInitCommand prints status to stdout; that stream must carry only
    // LSP frames once JDT LS is attached, so redirect it to stderr for this call.
    val initExit = withRedirectedStdout(System.err) {
      JdtlsInitCommand.main(initArgs.toTypedArray())
    }
    if (initExit != 0) return initExit

    val dataDir = dataDirOpt?.let { Paths.get(it).toAbsolutePath().normalize() }
      ?: issueDir.resolve(".lsp")
    Files.createDirectories(dataDir)

    val home = jdtlsHomeOpt?.let { Paths.get(it).toAbsolutePath().normalize() }
      ?: resolveJdtlsHome(downloadOpt)
      ?: return 1
    val launcherJar = JdtlsRuntime.findLauncherJar(home)
    val configDir = home.resolve(JdtlsRuntime.selectConfigDir())
    if (!Files.isDirectory(configDir)) {
      System.err.println("JDT LS config directory not found: $configDir")
      return 1
    }

    val process = ProcessBuilder(buildCommand(launcherJar, configDir, dataDir))
      .directory(issueDir.toFile())
      .inheritIO()
      .start()
    return process.waitFor()
  }

  private fun resolveJdtlsHome(download: Boolean): Path? {
    val artifact = JdtlsRuntime.resolveArtifact()
    JdtlsRuntime.findCached(artifact)?.let { return it }
    if (download) return JdtlsRuntime.ensureCached(artifact)
    System.err.println(
      "No cached JDT LS distribution found for ${artifact.label} " +
        "(expected under ${JdtlsRuntime.cacheRoot(artifact)}). " +
        "Pass --download to fetch it, or --jdtls-home to point at an existing install."
    )
    return null
  }

  private fun buildCommand(launcherJar: Path, configDir: Path, dataDir: Path): List<String> {
    val vmArgs = listOf(
      "-Declipse.application=org.eclipse.jdt.ls.core.id1",
      "-Dosgi.bundles.defaultStartLevel=4",
      "-Declipse.product=org.eclipse.jdt.ls.core.product",
      "-Dlog.level=ALL",
      "-Xmx1G",
      "--add-modules=ALL-SYSTEM",
      "--add-opens", "java.base/java.util=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
    return listOf("java") + vmArgs + listOf(
      "-jar",
      launcherJar.toAbsolutePath().normalize().toString(),
      "-configuration",
      configDir.toAbsolutePath().normalize().toString(),
      "-data",
      dataDir.toAbsolutePath().normalize().toString()
    )
  }

  private fun <T> withRedirectedStdout(target: PrintStream, block: () -> T): T {
    val original = System.out
    return try {
      System.setOut(target)
      block()
    } finally {
      System.setOut(original)
    }
  }
}
