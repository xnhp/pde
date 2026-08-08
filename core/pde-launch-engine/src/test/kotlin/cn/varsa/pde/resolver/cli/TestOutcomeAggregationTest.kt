package cn.varsa.pde.resolver.cli

import cn.varsa.pde.remoterunner.RemoteTestDescriptor
import cn.varsa.pde.remoterunner.RemoteTestResult
import cn.varsa.pde.remoterunner.RemoteTestStatus
import cn.varsa.pde.remoterunner.RemoteTestSummary
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestOutcomeAggregationTest {

  private fun result(className: String, name: String, status: RemoteTestStatus = RemoteTestStatus.PASSED) =
    RemoteTestResult(
      descriptor = RemoteTestDescriptor(
        id = "$className#$name",
        rawName = "$name($className)",
        className = className,
        methodName = name,
        displayName = name,
        printableName = "$name($className)"
      ),
      status = status,
      startedAt = Instant.EPOCH,
      finishedAt = Instant.EPOCH.plusMillis(5),
      expected = null,
      actual = null,
      trace = null
    )

  @Test
  fun `several launches fold into one summary and result list`() {
    val first = TestLaunchOutcome(
      exitCode = 0,
      summary = RemoteTestSummary(startedAt = Instant.EPOCH.plusSeconds(30)).apply {
        finishedTests = 2
        elapsedMillis = 100
        endedAt = Instant.EPOCH.plusSeconds(40)
      },
      results = listOf(result("org.example.ATest", "one"), result("org.example.ATest", "two"))
    )
    val second = TestLaunchOutcome(
      exitCode = 1,
      summary = RemoteTestSummary(startedAt = Instant.EPOCH.plusSeconds(10)).apply {
        finishedTests = 3
        failures = 1
        errors = 2
        stopped = true
        elapsedMillis = 50
        endedAt = Instant.EPOCH.plusSeconds(60)
      },
      results = listOf(result("org.example.BTest", "three", RemoteTestStatus.FAILED))
    )

    val (summary, results) = aggregateTestOutcomes(listOf(first, second))!!

    assertEquals(5, summary.finishedTests)
    assertEquals(1, summary.failures)
    assertEquals(2, summary.errors)
    assertTrue(summary.stopped)
    assertEquals(150, summary.elapsedMillis)
    assertEquals(Instant.EPOCH.plusSeconds(10), summary.startedAt)
    assertEquals(Instant.EPOCH.plusSeconds(60), summary.endedAt)
    assertEquals(
      listOf("org.example.ATest", "org.example.ATest", "org.example.BTest"),
      results.map { it.descriptor.className }
    )
  }

  @Test
  fun `a launch that never connected contributes nothing`() {
    val failed = TestLaunchOutcome(exitCode = 3)
    val ran = TestLaunchOutcome(
      exitCode = 0,
      summary = RemoteTestSummary().apply { finishedTests = 1 },
      results = listOf(result("org.example.ATest", "one"))
    )

    val (summary, results) = aggregateTestOutcomes(listOf(failed, ran))!!

    assertEquals(1, summary.finishedTests)
    assertEquals(1, results.size)
    assertNull(aggregateTestOutcomes(listOf(failed)))
  }
}
