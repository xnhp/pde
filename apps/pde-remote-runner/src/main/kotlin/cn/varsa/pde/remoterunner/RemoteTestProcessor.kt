package cn.varsa.pde.remoterunner

import cn.varsa.pde.remoterunner.protocol.MessageIds
import java.io.BufferedReader
import java.net.SocketException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

enum class RemoteTestStatus { PASSED, FAILED, ERROR }

data class RemoteTestDescriptor(
  val id: String,
  val rawName: String,
  val className: String,
  val methodName: String?,
  val displayName: String?,
  val printableName: String
)

data class RemoteTestResult(
  val descriptor: RemoteTestDescriptor,
  val status: RemoteTestStatus,
  val startedAt: Instant,
  val finishedAt: Instant,
  val expected: String?,
  val actual: String?,
  val trace: String?
)

data class RunStartedEvent(val totalTests: Int?, val protocolVersion: String?)

data class RemoteTestSummary(
  var finishedTests: Int = 0,
  var failures: Int = 0,
  var errors: Int = 0,
  var elapsedMillis: Long? = null,
  var stopped: Boolean = false,
  val startedAt: Instant = Instant.now(),
  var endedAt: Instant? = null
)

interface RemoteTestListener {
  fun runStarted(event: RunStartedEvent) {}
  fun testStarted(descriptor: RemoteTestDescriptor) {}
  fun testFinished(result: RemoteTestResult) {}
  fun runEnded(summary: RemoteTestSummary) {}
  fun runStopped(summary: RemoteTestSummary) {}
}

class CompositeRemoteTestListener(listeners: List<RemoteTestListener>) : RemoteTestListener {
  private val delegates = CopyOnWriteArrayList(listeners)

  override fun runStarted(event: RunStartedEvent) = delegates.forEach { it.runStarted(event) }
  override fun testStarted(descriptor: RemoteTestDescriptor) = delegates.forEach { it.testStarted(descriptor) }
  override fun testFinished(result: RemoteTestResult) = delegates.forEach { it.testFinished(result) }
  override fun runEnded(summary: RemoteTestSummary) = delegates.forEach { it.runEnded(summary) }
  override fun runStopped(summary: RemoteTestSummary) = delegates.forEach { it.runStopped(summary) }
}

class RemoteTestProcessor(private val listener: RemoteTestListener) {
  fun process(reader: BufferedReader): RemoteTestSummary {
    val summary = RemoteTestSummary()
    val active = mutableMapOf<String, ActiveTest>()
    val displayNames = mutableMapOf<String, String?>()
    var currentTestId: String? = null
    var runCompleted = false
    try {
      while (true) {
        val rawLine = reader.readLine() ?: break
        if (rawLine.isBlank()) continue
        when {
          rawLine.startsWith(MessageIds.TEST_RUN_START) -> handleRunStart(rawLine)
          rawLine.startsWith(MessageIds.TEST_TREE) -> handleTestTree(rawLine, displayNames)
          rawLine.startsWith(MessageIds.TEST_START) -> {
            val descriptor = parseDescriptor(rawLine, displayNames)
            val case = ActiveTest(descriptor)
            active[descriptor.id] = case
            currentTestId = descriptor.id
            listener.testStarted(descriptor)
          }
          rawLine.startsWith(MessageIds.TEST_FAILED) -> {
            val descriptor = parseDescriptor(rawLine, displayNames)
            val case = active[descriptor.id]
            case?.status = RemoteTestStatus.FAILED
            currentTestId = descriptor.id
          }
          rawLine.startsWith(MessageIds.TEST_ERROR) -> {
            val descriptor = parseDescriptor(rawLine, displayNames)
            val case = active[descriptor.id]
            case?.status = RemoteTestStatus.ERROR
            currentTestId = descriptor.id
          }
          rawLine.startsWith(MessageIds.EXPECTED_START) ->
            readSection(reader, MessageIds.EXPECTED_END)?.let { text ->
              currentTestId?.let { active[it]?.expected = text }
            }
          rawLine.startsWith(MessageIds.ACTUAL_START) ->
            readSection(reader, MessageIds.ACTUAL_END)?.let { text ->
              currentTestId?.let { active[it]?.actual = text }
            }
          rawLine.startsWith(MessageIds.TRACE_START) ->
            readSection(reader, MessageIds.TRACE_END)?.let { text ->
              currentTestId?.let { active[it]?.trace = text }
            }
          rawLine.startsWith(MessageIds.TEST_END) -> {
            val descriptor = parseDescriptor(rawLine, displayNames)
            currentTestId = null
            val case = active.remove(descriptor.id)
            if (case != null) {
              val result = case.toResult()
              summary.finishedTests++
              when (result.status) {
                RemoteTestStatus.FAILED -> summary.failures++
                RemoteTestStatus.ERROR -> summary.errors++
                else -> Unit
              }
              listener.testFinished(result)
            }
          }
          rawLine.startsWith(MessageIds.TEST_RUN_END) -> {
            val elapsed = rawLine.substring(MessageIds.MSG_HEADER_LENGTH).trim().toLongOrNull()
            summary.elapsedMillis = elapsed
            summary.endedAt = Instant.now()
            listener.runEnded(summary)
            runCompleted = true
          }
          rawLine.startsWith(MessageIds.TEST_STOPPED) -> {
            summary.stopped = true
            summary.endedAt = Instant.now()
            listener.runStopped(summary)
          }
        }
      }
    } catch (_: SocketException) {
      summary.stopped = true
    }
    if (!runCompleted && !summary.stopped) {
      summary.stopped = true
      summary.endedAt = Instant.now()
      listener.runStopped(summary)
    }
    return summary
  }

  private fun handleRunStart(line: String) {
    val payload = line.substring(MessageIds.MSG_HEADER_LENGTH).trim()
    if (payload.isEmpty()) {
      listener.runStarted(RunStartedEvent(null, null))
      return
    }
    val parts = payload.split(' ', limit = 2)
    val total = parts.getOrNull(0)?.toIntOrNull()
    val version = parts.getOrNull(1)
    listener.runStarted(RunStartedEvent(total, version))
  }

  private fun handleTestTree(line: String, names: MutableMap<String, String?>) {
    val payload = line.substring(MessageIds.MSG_HEADER_LENGTH)
    val parts = payload.split(',', limit = 9)
    if (parts.isNotEmpty()) {
      val id = parts[0]
      val displayName = parts.getOrNull(6)?.takeUnless { it.isBlank() }
      names[id] = displayName
    }
  }

  private fun parseDescriptor(line: String, names: Map<String, String?>): RemoteTestDescriptor {
    val payload = line.substring(MessageIds.MSG_HEADER_LENGTH)
    val comma = payload.indexOf(',')
    require(comma > 0) { "Malformed remote test payload: $payload" }
    val id = payload.substring(0, comma)
    val name = normalizeTestName(payload.substring(comma + 1))
    val open = name.indexOf('(')
    val close = name.lastIndexOf(')')
    val method = if (open > 0 && close > open) name.substring(0, open) else null
    val clazz = if (open > 0 && close > open) name.substring(open + 1, close) else name
    val display = names[id]
    val printable = display ?: if (method != null) "$clazz.$method" else clazz
    return RemoteTestDescriptor(
      id = id,
      rawName = name,
      className = clazz,
      methodName = method,
      displayName = display,
      printableName = printable
    )
  }

  private fun normalizeTestName(name: String): String {
    return name
      .removePrefix(MessageIds.IGNORED_TEST_PREFIX)
      .removePrefix(MessageIds.ASSUMPTION_FAILED_TEST_PREFIX)
      .trim()
  }

  private fun readSection(reader: BufferedReader, endMarker: String): String? {
    val builder = StringBuilder()
    while (true) {
      val line = reader.readLine() ?: break
      if (line.startsWith(endMarker)) break
      if (builder.isNotEmpty()) builder.append('\n')
      builder.append(line)
    }
    return if (builder.isNotEmpty()) builder.toString() else null
  }

  private data class ActiveTest(
    val descriptor: RemoteTestDescriptor,
    var status: RemoteTestStatus = RemoteTestStatus.PASSED,
    var expected: String? = null,
    var actual: String? = null,
    var trace: String? = null,
    val startedAt: Instant = Instant.now()
  ) {
    fun toResult(): RemoteTestResult = RemoteTestResult(
      descriptor = descriptor,
      status = status,
      startedAt = startedAt,
      finishedAt = Instant.now(),
      expected = expected,
      actual = actual,
      trace = trace
    )
  }
}
