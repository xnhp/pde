package cn.varsa.pde.remoterunner

import java.io.PrintStream
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

class LoggingRemoteTestListener(
  private val out: PrintStream,
  private val includes: List<Regex> = emptyList(),
  private val excludes: List<Regex> = emptyList(),
  private val color: Boolean = false
) : RemoteTestListener {
  private val isoFormatter = DateTimeFormatter.ISO_INSTANT
  private val reset = "\u001B[0m"
  private val green = "\u001B[32m"
  private val red = "\u001B[31m"
  private val yellow = "\u001B[33m"
  private val cyan = "\u001B[36m"
  private val blue = "\u001B[34m"

  override fun runStarted(event: RunStartedEvent) {
    val countText = event.totalTests?.toString() ?: "unknown"
    val versionText = event.protocolVersion ?: "?"
    out.println("${paintInfo("[INFO]")} Remote test session started (tests=$countText, protocol=$versionText)")
  }

  override fun testStarted(descriptor: RemoteTestDescriptor) {
    if (shouldLog(descriptor.printableName)) {
      out.println("${paintCyan("[TEST]")} START ${descriptor.printableName}")
    }
  }

  override fun testFinished(result: RemoteTestResult) {
    if (!shouldLog(result.descriptor.printableName)) return
    val duration = Duration.between(result.startedAt, result.finishedAt).toMillis()
    val prefix = when (result.status) {
      RemoteTestStatus.PASSED -> paintGreen("[PASS]")
      RemoteTestStatus.FAILED -> paintRed("[FAIL]")
      RemoteTestStatus.ERROR -> paintRed("[ERROR]")
    }
    out.println("$prefix ${result.descriptor.printableName} (${duration}ms)")
    if (result.status != RemoteTestStatus.PASSED) {
      result.expected?.let { out.println("  expected: $it") }
      result.actual?.let { out.println("  actual: $it") }
      result.trace?.let {
        out.println("  trace:")
        it.lineSequence().forEach { line -> out.println("    $line") }
      }
    }
  }

  override fun runEnded(summary: RemoteTestSummary) {
    val elapsed = summary.elapsedMillis ?: summary.endedAt?.let { Duration.between(summary.startedAt, it).toMillis() }
    out.println("${paintInfo("[INFO]")} Remote test session finished at ${summary.endedAt?.atZone(ZoneOffset.UTC)?.let(isoFormatter::format) ?: "unknown"}")
    out.println("${paintInfo("[INFO]")} Tests=${summary.finishedTests} failed=${summary.failures} errors=${summary.errors} elapsedMs=${elapsed ?: -1}")
  }

  override fun runStopped(summary: RemoteTestSummary) {
    out.println("${paintYellow("[WARN]")} Remote test session stopped before completion")
    runEnded(summary)
  }

  private fun paintGreen(text: String) = if (color) "$green$text$reset" else text
  private fun paintRed(text: String) = if (color) "$red$text$reset" else text
  private fun paintYellow(text: String) = if (color) "$yellow$text$reset" else text
  private fun paintInfo(text: String) = if (color) "$blue$text$reset" else text
  private fun paintCyan(text: String) = if (color) "$cyan$text$reset" else text

  private fun shouldLog(name: String): Boolean {
    val includeOk = includes.isEmpty() || includes.any { it.containsMatchIn(name) }
    val excludeHit = excludes.any { it.containsMatchIn(name) }
    return includeOk && !excludeHit
  }
}
