package cn.varsa.pde.remoterunner

import cn.varsa.pde.remoterunner.protocol.MessageIds
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteRunnerAppTest {
  @Test
  fun writesJUnitReportAndCountsFailures() {
    val reportFile = Files.createTempFile("remoterunner", ".xml")
    val port = ServerSocket(0).use { it.localPort }
    val exitCode = AtomicInteger(-1)
    val runner = Thread {
      exitCode.set(
        RemoteRunnerApp().run(
          arrayOf(
            "--listen-port", port.toString(),
            "--timeout", "5",
            "--report", "junit-xml:${reportFile}"
          )
        )
      )
    }
    runner.start()
    Thread.sleep(200) // give the server time to bind

    Socket("127.0.0.1", port).use { socket ->
      val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
      writer.write(MessageIds.TEST_RUN_START + "2 1"); writer.newLine()
      writer.write(MessageIds.TEST_START + "1,example.FailingTest"); writer.newLine()
      writer.write(MessageIds.TEST_FAILED + "1,example.FailingTest"); writer.newLine()
      writer.write(MessageIds.TRACE_START); writer.newLine()
      writer.write("Assertion failed"); writer.newLine()
      writer.write(MessageIds.TRACE_END); writer.newLine()
      writer.write(MessageIds.TEST_END + "1,example.FailingTest"); writer.newLine()
      writer.write(MessageIds.TEST_START + "2,example.PassingTest"); writer.newLine()
      writer.write(MessageIds.TEST_END + "2,example.PassingTest"); writer.newLine()
      writer.write(MessageIds.TEST_RUN_END + "123"); writer.newLine()
      writer.flush()
    }

    runner.join(2_000)

    assertEquals(1, exitCode.get(), "exit code reflects failing tests")
    val xml = Files.readString(reportFile)
    assertTrue(xml.contains("failures=\"1\""), "JUnit report contains failure count")
    assertTrue(xml.contains("example.FailingTest"), "JUnit report lists failing test")
  }
}
