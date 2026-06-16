package cn.varsa.pde.launch

import cn.varsa.cli.core.CliMcpRegistrationConfig
import cn.varsa.cli.core.runCliMcpStdioServer
import kotlinx.coroutines.runBlocking

fun runPdeMcpServer() = runBlocking {
  System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
  System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
  runCliMcpStdioServer(
    root = pdeMcpWorkflowCommand,
    name = "pde-cli",
    version = detectedVersion(),
    config = CliMcpRegistrationConfig()
  )
}

fun main(args: Array<String>) {
  if (args.isNotEmpty()) {
    System.err.println("Warning: ignoring CLI arguments ${args.joinToString(" ")}")
  }
  runPdeMcpServer()
}

private fun detectedVersion(): String {
  val pkg = object {}::class.java.`package`
  return pkg?.implementationVersion?.takeIf { it.isNotBlank() } ?: "0.0.0"
}
