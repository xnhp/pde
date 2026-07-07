package cn.varsa.pde.resolver.api

import org.eclipse.equinox.app.IApplication
import org.eclipse.equinox.app.IApplicationContext
import java.nio.file.Path

class DirectApiAnalyzerApplication : IApplication {
  override fun start(context: IApplicationContext): Any {
    val args = applicationArgs(context)
    val inputPath = parseInputPath(args)
    DirectApiAnalyzerHarness().analyze(DirectApiAnalyzerInputJson.read(inputPath))
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
  }
}
