package cn.varsa.pde.resolver.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.equinox.app.IApplication
import org.eclipse.equinox.app.IApplicationContext
import org.osgi.framework.Bundle
import org.osgi.framework.FrameworkUtil
import java.nio.file.Files
import java.nio.file.Path

class DirectApiAnalyzerApplication : IApplication {
  override fun start(context: IApplicationContext): Any {
    val args = applicationArgs(context)
    val inputPath = parseInputPath(args)
    return try {
      val input = readAnalyzerInput(inputPath)
      val result = DirectApiAnalyzerHarness().analyzeBatch(input)
      // Eclipse's launcher uses an Integer return value from IApplication#start as the process
      // exit code (IApplication.EXIT_OK is Integer 0). Continue-and-aggregate: every bundle in
      // the batch is analyzed even if another one failed, but the whole process still exits
      // non-zero if any bundle's analysis failed.
      if (result.allSucceeded) IApplication.EXIT_OK else EXIT_ANALYSIS_FAILED
    } finally {
      stopDebugBundlesBeforeRegistryShutdown()
    }
  }

  override fun stop() = Unit

  companion object {
    const val APPLICATION_ID = "cn.varsa.pde.api_analyzer"
    internal val EXIT_ANALYSIS_FAILED: Any = 1

    private val jsonProbeMapper = ObjectMapper()

    internal fun parseInputPath(args: Array<String>): Path {
      args.forEach { arg ->
        if (arg.startsWith("--input=")) {
          return Path.of(arg.substringAfter('='))
        }
      }
      val index = args.indexOfFirst { it == "--input" || it == "-input" }
      require(index >= 0 && index < args.lastIndex) {
        "Missing analyzer input. Pass --input <path>."
      }
      return Path.of(args[index + 1])
    }

    /**
     * Reads the analyzer input file, accepting both the batch format ("currentBundles") the CLI
     * writes today and the legacy single-bundle format ("currentBundle") kept for callers that
     * still construct a [DirectApiAnalyzerInput] directly (e.g. lower-level harness tests). The
     * legacy format is treated as a batch of one.
     */
    internal fun readAnalyzerInput(path: Path): BatchApiAnalyzerInput {
      val tree = jsonProbeMapper.readTree(Files.newInputStream(path))
      return if (tree.has("currentBundles")) {
        BatchApiAnalyzerInputJson.read(path)
      } else {
        val single = DirectApiAnalyzerInputJson.read(path)
        BatchApiAnalyzerInput(
          currentBundles = listOf(
            CurrentBundleInfo(
              currentBundle = single.currentBundle,
              outputReportPath = single.outputReportPath,
              apiFilterPath = single.apiFilterFile
            )
          ),
          dependencyArtifacts = single.dependencyArtifacts,
          baselineArtifacts = single.baselineArtifacts,
          preferences = single.preferences
        )
      }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applicationArgs(context: IApplicationContext): Array<String> {
      val arguments = context.arguments[IApplicationContext.APPLICATION_ARGS]
      return arguments as? Array<String> ?: emptyArray()
    }

    private fun stopDebugBundlesBeforeRegistryShutdown() {
      val context = FrameworkUtil.getBundle(DirectApiAnalyzerApplication::class.java)?.bundleContext ?: return
      listOf(
        "org.eclipse.jdt.debug",
        "org.eclipse.jdt.launching",
        "org.eclipse.debug.core"
      ).forEach { symbolicName ->
        val bundle = context.bundles.firstOrNull { it.symbolicName == symbolicName } ?: return@forEach
        if (bundle.state == Bundle.ACTIVE || bundle.state == Bundle.STARTING) {
          bundle.stop(Bundle.STOP_TRANSIENT)
        }
      }
    }
  }
}
