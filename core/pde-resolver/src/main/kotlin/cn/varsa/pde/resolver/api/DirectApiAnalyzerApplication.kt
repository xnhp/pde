package cn.varsa.pde.resolver.api

import org.eclipse.equinox.app.IApplication
import org.eclipse.equinox.app.IApplicationContext
import org.osgi.framework.Bundle
import org.osgi.framework.FrameworkUtil
import java.nio.file.Path

class DirectApiAnalyzerApplication : IApplication {
  override fun start(context: IApplicationContext): Any {
    val args = applicationArgs(context)
    val inputPath = parseInputPath(args)
    try {
      DirectApiAnalyzerHarness().analyze(DirectApiAnalyzerInputJson.read(inputPath))
    } finally {
      stopDebugBundlesBeforeRegistryShutdown()
    }
    return IApplication.EXIT_OK
  }

  override fun stop() = Unit

  companion object {
    const val APPLICATION_ID = "cn.varsa.pde.api_analyzer"

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
