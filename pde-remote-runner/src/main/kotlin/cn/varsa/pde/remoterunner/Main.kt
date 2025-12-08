package cn.varsa.pde.remoterunner

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess
import kotlin.concurrent.thread

fun main(args: Array<String>) {
  val exitCode = RemoteRunnerApp().run(args)
  exitProcess(exitCode)
}

class RemoteRunnerApp {
  private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

  fun run(args: Array<String>): Int {
    val parser = ArgParser("pde-remote-runner")
    val listenHost by parser.option(ArgType.String, fullName = "listen-host", description = "Host to bind").default("127.0.0.1")
    val listenPort by parser.option(ArgType.Int, fullName = "listen-port", description = "Fixed port to bind")
    val portRangeSpec by parser.option(ArgType.String, fullName = "port-range", description = "Inclusive port range start-end")
    val timeoutSeconds by parser.option(ArgType.Int, fullName = "timeout", description = "Seconds to wait for PDE connection").default(180)
    val reportValues by parser.option(ArgType.String, fullName = "report", description = "Reporting sink (teamcity, junit-xml:/path)").multiple()
    val forwardValues by parser.option(ArgType.String, fullName = "forward-log", description = "Forward log in form label=path").multiple()
    val includePatterns by parser.option(ArgType.String, fullName = "include", description = "Regex filter to include tests").multiple()
    val excludePatterns by parser.option(ArgType.String, fullName = "exclude", description = "Regex filter to exclude tests").multiple()
    parser.parse(args)

    val reports = runCatching { reportValues.map(::parseReportTarget) }
      .getOrElse { error ->
        System.err.println("Invalid --report value: ${error.message}")
        return 2
      }
    val forwardSpecs = runCatching { forwardValues.map(::parseForwardSpec) }
      .getOrElse { error ->
        System.err.println("Invalid --forward-log value: ${error.message}")
        return 2
      }
    val includes = runCatching { includePatterns.map(::toRegexSafe) }
      .getOrElse { error ->
        System.err.println("Invalid --include regex: ${error.message}")
        return 2
      }
    val excludes = runCatching { excludePatterns.map(::toRegexSafe) }
      .getOrElse { error ->
        System.err.println("Invalid --exclude regex: ${error.message}")
        return 2
      }

    val allocator = PortAllocator(listenHost, listenPort, parsePortRange(portRangeSpec))
    val server = try {
      allocator.bind()
    } catch (ex: Exception) {
      System.err.println("Failed to bind server socket: ${ex.message}")
      return 2
    }

    server.soTimeout = timeoutSeconds.coerceAtLeast(1) * 1000
    val announcement = RunnerAnnouncement(
      host = listenHost,
      port = server.localPort,
      timeoutSeconds = timeoutSeconds,
      instructions = listOf(
        "Add '-port ${server.localPort}' to PDE launch program arguments.",
        "Example: pde-launch --programArg \"-port ${server.localPort}\""
      ),
      issuedAt = Instant.now().toString()
    )
    println(mapper.writeValueAsString(announcement))
    println("[INFO] Listening on ${announcement.host}:${announcement.port}")
    println("[INFO] Waiting up to ${timeoutSeconds}s for RemoteTestRunner connection...")

    val client = try {
      server.accept()
    } catch (timeout: SocketTimeoutException) {
      System.err.println("Timed out waiting for PDE connection after ${timeoutSeconds}s")
      server.close()
      return 3
    }
    println("[INFO] Connection established from ${client.inetAddress.hostAddress}:${client.port}")

    startForwarders(forwardSpecs)

    val listeners = mutableListOf<RemoteTestListener>()
    listeners += LoggingRemoteTestListener(System.out, includes, excludes)
    val recorder = RecordingRemoteTestListener()
    listeners += recorder
    if (reports.any { it is ReportTarget.TeamCity }) {
      listeners += TeamCityRemoteTestListener(System.out)
    }
    val composite = CompositeRemoteTestListener(listeners)
    val processor = RemoteTestProcessor(composite)

    val summary: RemoteTestSummary
    client.getInputStream().bufferedReader(StandardCharsets.UTF_8).use { reader ->
      summary = processor.process(reader)
    }
    client.close()
    server.close()

    reports.filterIsInstance<ReportTarget.JUnitXml>().forEach { target ->
      try {
        JUnitXmlReporter(target.path).write(summary, recorder.results)
        println("[INFO] Wrote JUnit report to ${target.path}")
      } catch (ex: Exception) {
        System.err.println("Failed to write JUnit report: ${ex.message}")
      }
    }

    val exitCode = if (summary.failures == 0 && summary.errors == 0 && !summary.stopped) 0 else 1
    if (exitCode != 0) {
      System.err.println("Remote tests reported failures=${summary.failures} errors=${summary.errors} stopped=${summary.stopped}")
    }
    return exitCode
  }

  private fun toRegexSafe(pattern: String): Regex = Regex(pattern)
}

data class RunnerAnnouncement(
  val host: String,
  val port: Int,
  val timeoutSeconds: Int,
  val instructions: List<String>,
  val issuedAt: String
)

data class ForwardLogSpec(val label: String, val path: Path)

fun parseForwardSpec(value: String): ForwardLogSpec {
  val idx = value.indexOf('=')
  require(idx > 0) { "--forward-log expects label=path" }
  val label = value.substring(0, idx).trim()
  val path = value.substring(idx + 1).trim()
  require(label.isNotBlank()) { "Forward log label must not be blank" }
  require(path.isNotBlank()) { "Forward log path must not be blank" }
  return ForwardLogSpec(label, Path.of(path))
}

fun startForwarders(specs: List<ForwardLogSpec>): List<Thread> = specs.map { spec ->
  thread(name = "forward-${spec.label}", isDaemon = true) {
    try {
      Files.newInputStream(spec.path).bufferedReader().useLines { lines ->
        lines.forEach { line -> println("[${spec.label}] $line") }
      }
    } catch (ex: Exception) {
      System.err.println("Failed to forward ${spec.label}: ${ex.message}")
    }
  }
}
