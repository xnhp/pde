package cn.varsa.pde.remoterunner

import java.io.PrintStream
import java.time.Duration

class TeamCityRemoteTestListener(private val out: PrintStream) : RemoteTestListener {
  private val suiteActiveCounts = mutableMapOf<String, Int>()

  override fun testStarted(descriptor: RemoteTestDescriptor) {
    startSuiteIfNeeded(descriptor.className)
    out.println(teamCityMessage("testStarted", mapOf(
      "name" to descriptor.printableName,
      "captureStandardOutput" to "true"
    )))
  }

  override fun testFinished(result: RemoteTestResult) {
    if (result.status != RemoteTestStatus.PASSED) {
      val message = if (result.status == RemoteTestStatus.ERROR) "Error" else "Failure"
      val details = buildFailureDetails(result)
      out.println(teamCityMessage("testFailed", mapOf(
        "name" to result.descriptor.printableName,
        "message" to message,
        "details" to details
      )))
    }
    val duration = Duration.between(result.startedAt, result.finishedAt).toMillis().coerceAtLeast(0)
    out.println(teamCityMessage("testFinished", mapOf(
      "name" to result.descriptor.printableName,
      "duration" to duration.toString()
    )))
    finishSuiteIfNeeded(result.descriptor.className)
  }

  override fun runStopped(summary: RemoteTestSummary) {
    flushSuites()
  }

  override fun runEnded(summary: RemoteTestSummary) {
    flushSuites()
  }

  private fun startSuiteIfNeeded(className: String) {
    val current = suiteActiveCounts.getOrDefault(className, 0)
    if (current == 0) {
      out.println(teamCityMessage("testSuiteStarted", mapOf("name" to className)))
    }
    suiteActiveCounts[className] = current + 1
  }

  private fun finishSuiteIfNeeded(className: String) {
    val remaining = suiteActiveCounts.getOrDefault(className, 0) - 1
    if (remaining <= 0) {
      suiteActiveCounts.remove(className)
      out.println(teamCityMessage("testSuiteFinished", mapOf("name" to className)))
    } else {
      suiteActiveCounts[className] = remaining
    }
  }

  private fun flushSuites() {
    val open = suiteActiveCounts.keys.toList()
    open.forEach { className ->
      out.println(teamCityMessage("testSuiteFinished", mapOf("name" to className)))
    }
    suiteActiveCounts.clear()
  }

  private fun teamCityMessage(event: String, attributes: Map<String, String>): String {
    val body = attributes.entries.joinToString(" ") { (key, value) -> "$key='${teamCityEscape(value)}'" }
    return "##teamcity[$event $body]"
  }

  private fun buildFailureDetails(result: RemoteTestResult): String {
    val trace = result.trace ?: ""
    val expected = result.expected?.let { "Expected: $it" }
    val actual = result.actual?.let { "Actual: $it" }
    return listOfNotNull(trace, expected, actual).joinToString("\n").ifBlank { "No details" }
  }

  private fun teamCityEscape(input: String): String {
    return buildString(input.length) {
      input.forEach { ch ->
        when (ch) {
          '|' -> append("||")
          '\'' -> append("|'")
          '\n' -> append("|n")
          '\r' -> append("|r")
          ']' -> append("|]")
          '[' -> append("|[")
          else -> append(ch)
        }
      }
    }
  }
}
