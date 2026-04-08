package cn.varsa.pde.remoterunner

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

sealed interface ReportTarget {
  data object TeamCity : ReportTarget
  data class JUnitXml(val path: Path) : ReportTarget
}

fun parseReportTarget(spec: String): ReportTarget {
  val trimmed = spec.trim()
  if (trimmed.equals("teamcity", ignoreCase = true)) return ReportTarget.TeamCity
  val matcher = Regex("^junit-xml[:=](.+)$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
  if (matcher != null) {
    val path = matcher.groupValues[1].trim()
    require(path.isNotBlank()) { "junit-xml report requires a path" }
    return ReportTarget.JUnitXml(Path.of(path))
  }
  error("Unsupported report target: $spec")
}

class JUnitXmlReporter(private val target: Path) {
  private val timestampFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  fun write(summary: RemoteTestSummary, results: List<RemoteTestResult>) {
    val parent = target.parent
    if (parent != null) Files.createDirectories(parent)
    val totalTimeMs = summary.elapsedMillis ?: results.sumOf { Duration.between(it.startedAt, it.finishedAt).toMillis() }
    val timeSeconds = totalTimeMs / 1000.0
    val timestamp = summary.startedAt.atOffset(ZoneOffset.UTC).format(timestampFormatter)
    val content = buildString {
      appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
      append("<testsuite name=\"RemotePDE\"")
      append(" tests=\"${summary.finishedTests}\"")
      append(" failures=\"${summary.failures}\"")
      append(" errors=\"${summary.errors}\"")
      append(" skipped=\"0\"")
      append(" time=\"${"%.3f".format(timeSeconds)}\"")
      append(" timestamp=\"$timestamp\"")
      appendLine(">")
      results.forEach { result ->
        val displayName = result.descriptor.displayName ?: result.descriptor.methodName ?: result.descriptor.printableName
        append("  <testcase classname=\"${xmlEscape(result.descriptor.className)}\" name=\"${xmlEscape(displayName)}\"")
        val duration = Duration.between(result.startedAt, result.finishedAt).toMillis() / 1000.0
        append(" time=\"${"%.3f".format(duration)}\"")
        appendLine(">");
        when (result.status) {
          RemoteTestStatus.FAILED -> appendFailureTag("failure", result)
          RemoteTestStatus.ERROR -> appendFailureTag("error", result)
          else -> Unit
        }
        appendLine("  </testcase>")
      }
      appendLine("</testsuite>")
    }
    Files.writeString(target, content)
  }

  private fun StringBuilder.appendFailureTag(tag: String, result: RemoteTestResult) {
    val message = result.trace?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    val bodyLines = buildList {
      result.trace?.let { add(it) }
      result.expected?.let { add("Expected: $it") }
      result.actual?.let { add("Actual: $it") }
    }
    append("    <$tag message=\"${xmlEscape(message)}\">")
    append(xmlEscape(bodyLines.joinToString("\n")))
    appendLine("</$tag>")
  }

  private fun xmlEscape(text: String): String {
    val builder = StringBuilder(text.length)
    text.forEach { ch ->
      when (ch) {
        '&' -> builder.append("&amp;")
        '<' -> builder.append("&lt;")
        '>' -> builder.append("&gt;")
        '\"' -> builder.append("&quot;")
        '\'' -> builder.append("&apos;")
        else -> builder.append(ch)
      }
    }
    return builder.toString()
  }
}
