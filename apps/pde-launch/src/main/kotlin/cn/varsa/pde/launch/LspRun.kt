package cn.varsa.pde.launch

import cn.varsa.pde.resolver.cli.WorkspaceLiveMarker
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

    // JDT LS and the pde Equinox apps (`pde jdt-workspace init/build`, `pde api-baseline check`)
    // must never share a `-data` directory: headless Equinox takes no .metadata/.lock, so two
    // writers silently corrupt the workspace. JDT LS gets <issue-dir>/.lsp, the Equinox apps get
    // <output-root>/data (default .jdtls/workspace/data).
    val dataDir = dataDirOpt?.let { Paths.get(it).toAbsolutePath().normalize() }
      ?: issueDir.resolve(".lsp")
    val configParents = listOfNotNull(configOpt, configPos).mapNotNull { Paths.get(it).toAbsolutePath().normalize().parent }
    val equinoxDataDirs = (listOf(issueDir) + configParents).map { it.resolve(".jdtls/workspace/data") }
    equinoxDataDirs.firstOrNull { sameDirectory(it, dataDir) }?.let { clash ->
      System.err.println(refusedDataDirMessage(dataDir, clash))
      return 2
    }
    WorkspaceLiveMarker.liveOwner(dataDir)?.let { owner ->
      System.err.println(WorkspaceLiveMarker.inUseMessage("pde lsp run", dataDir, owner, WorkspaceLiveMarker.HINT_OTHER_DATA_DIR))
      return 2
    }

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

    WorkspaceLiveMarker.liveOwner(dataDir)?.let { owner ->
      System.err.println(WorkspaceLiveMarker.inUseMessage("pde lsp run", dataDir, owner, WorkspaceLiveMarker.HINT_OTHER_DATA_DIR))
      return 2
    }
    return WorkspaceLiveMarker.hold(dataDir, "pde lsp run").use {
      val process = ProcessBuilder(buildCommand(launcherJar, configDir, dataDir))
        .directory(issueDir.toFile())
        .inheritIO()
        .start()
      process.waitFor()
    }
  }

  internal fun refusedDataDirMessage(dataDir: Path, equinoxDataDir: Path): String =
    "pde lsp run: --data-dir $dataDir is the pde Equinox workspace ($equinoxDataDir, used by " +
      "'pde jdt-workspace init/build' and 'pde api-baseline check'). Headless Equinox takes no workspace lock, " +
      "so JDT LS and those commands would write the same .metadata and corrupt it. Pass a different --data-dir " +
      "(default: <issue-dir>/.lsp)."

  private fun sameDirectory(a: Path, b: Path): Boolean {
    if (a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()) return true
    return try {
      Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)
    } catch (_: java.io.IOException) {
      false
    }
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
