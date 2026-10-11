package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.cli.config.SidecarEntry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidecarRunnerTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  private fun captureLogs(block: (List<String>) -> Unit) {
    val logger = Logger.getLogger(SidecarRunner::class.java.name)
    val messages = mutableListOf<String>()
    val handler = object : Handler() {
      override fun publish(record: LogRecord) {
        synchronized(messages) { messages.add(record.message) }
      }

      override fun flush() = Unit

      override fun close() = Unit
    }
    val previousLevel = logger.level
    logger.level = Level.INFO
    logger.addHandler(handler)
    try {
      block(messages)
    } finally {
      logger.removeHandler(handler)
      logger.level = previousLevel
    }
  }

  private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) error("Condition not met within $timeoutMillis ms")
      Thread.sleep(50)
    }
  }

  @Test
  fun relaysPrefixedOutputReportsReadyAndStopsOnRequest() {
    val root = tmp.root.toPath()
    val buffer = ByteArrayOutputStream()
    val out = PrintStream(buffer, true, StandardCharsets.UTF_8)
    val entry = SidecarEntry(
      name = "web",
      command = listOf("sh", "-c", "echo starting; echo \"Local:   http://localhost:3000/\"; sleep 30"),
      readyPattern = "Local:\\s+(http\\S+)"
    )

    captureLogs { messages ->
      val runner = SidecarRunner.start(listOf(entry), root, out = out)
      waitUntil { synchronized(messages) { messages.any { it.startsWith("[web] ready:") } } }
      assertTrue(runner.isAlive("web"))

      runner.stop(graceMillis = 2_000)

      waitUntil { !runner.isAlive("web") }
      val lines = buffer.toString(StandardCharsets.UTF_8).lines().filter { it.isNotBlank() }
      assertEquals(listOf("[web] starting", "[web] Local:   http://localhost:3000/"), lines)
      assertTrue(messages.any { it == "[web] ready: http://localhost:3000/" }, "Expected ready log, got $messages")
      assertTrue(messages.any { it == "[web] stopped" }, "Expected stopped log, got $messages")
    }
  }

  @Test
  fun readyWithoutGroupUsesWholeLine() {
    val root = tmp.root.toPath()
    val out = PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
    val entry = SidecarEntry(name = "x", command = listOf("sh", "-c", "echo server up"), readyPattern = "server up")

    captureLogs { messages ->
      val runner = SidecarRunner.start(listOf(entry), root, out = out)
      waitUntil { synchronized(messages) { messages.any { it == "[x] ready: server up" } } }
      runner.stop(graceMillis = 1_000)
    }
  }

  @Test
  fun environmentAndCwdAreApplied() {
    val root = tmp.root.toPath()
    val sub = tmp.newFolder("work").toPath()
    val buffer = ByteArrayOutputStream()
    val out = PrintStream(buffer, true, StandardCharsets.UTF_8)
    val entry = SidecarEntry(
      name = "env",
      command = listOf("sh", "-c", "echo \"\$FROM_LAUNCH \$FROM_SIDECAR \$(pwd)\""),
      cwd = "work",
      env = mapOf("FROM_SIDECAR" to "s")
    )

    captureLogs { messages ->
      val runner = SidecarRunner.start(listOf(entry), root, launchEnv = mapOf("FROM_LAUNCH" to "l"), out = out)
      waitUntil { !runner.isAlive("env") }
      runner.stop(graceMillis = 1_000)
      val line = buffer.toString(StandardCharsets.UTF_8).trim()
      assertEquals("[env] l s ${sub.toRealPath()}", line)
      assertFalse(messages.any { it.contains("exited with code") }, "Unexpected exit warning: $messages")
    }
  }

  @Test
  fun earlyNonZeroExitIsLoggedAsWarning() {
    val root = tmp.root.toPath()
    val out = PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
    val entry = SidecarEntry(name = "bad", command = listOf("sh", "-c", "exit 3"))

    captureLogs { messages ->
      val runner = SidecarRunner.start(listOf(entry), root, out = out)
      waitUntil { synchronized(messages) { messages.any { it == "[bad] exited with code 3" } } }
      runner.stop(graceMillis = 1_000)
    }
  }

  @Test
  fun missingCwdFails() {
    val root = tmp.root.toPath()
    val entry = SidecarEntry(name = "nope", command = listOf("sh", "-c", "true"), cwd = "does-not-exist")

    assertFailsWith<IllegalArgumentException> {
      SidecarRunner.start(listOf(entry), root, out = PrintStream(ByteArrayOutputStream()))
    }
  }
}
