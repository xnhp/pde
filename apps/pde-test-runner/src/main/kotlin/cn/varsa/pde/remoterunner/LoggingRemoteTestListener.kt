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

  override fun runStarted(event: RunStartedEvent) {
    val countText = event.totalTests?.toString() ?: "unknown"
    val versionText = event.protocolVersion ?: "?"
    out.println("${ConsoleTags.info(color)} Remote test session started (tests=$countText, protocol=$versionText)")
  }

  override fun testStarted(descriptor: RemoteTestDescriptor) {
    if (shouldLog(descriptor.printableName)) {
      out.println("${ConsoleTags.test(color)} START ${descriptor.printableName}")
    }
  }

  override fun testFinished(result: RemoteTestResult) {
    if (!shouldLog(result.descriptor.printableName)) return
    val duration = Duration.between(result.startedAt, result.finishedAt).toMillis()
    val prefix = when (result.status) {
      RemoteTestStatus.PASSED -> ConsoleTags.pass(color)
      RemoteTestStatus.FAILED -> ConsoleTags.fail(color)
      RemoteTestStatus.ERROR -> ConsoleTags.error(color)
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
    out.println("${ConsoleTags.info(color)} Remote test session finished at ${summary.endedAt?.atZone(ZoneOffset.UTC)?.let(isoFormatter::format) ?: "unknown"}")
    out.println("${ConsoleTags.info(color)} Tests=${summary.finishedTests} failed=${summary.failures} errors=${summary.errors} elapsedMs=${elapsed ?: -1}")
  }

  override fun runStopped(summary: RemoteTestSummary) {
    out.println("${ConsoleTags.warn(color)} Remote test session stopped before completion")
    runEnded(summary)
  }

  private fun shouldLog(name: String): Boolean {
    val includeOk = includes.isEmpty() || includes.any { it.containsMatchIn(name) }
    val excludeHit = excludes.any { it.containsMatchIn(name) }
    return includeOk && !excludeHit
  }
}
