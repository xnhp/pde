package cn.varsa.pde.resolver.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.io.path.writeText

class ApiAnalysisSanityTest {
  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `degraded heuristic fires on the observed 428-problem signature`() {
    // gateway-impl-check3.json: 309 "no longer an API" types, 81 unused filters of ~90 rules.
    val message = ApiAnalysisSanity.degradedResultMessage(
      noLongerApiTypeCount = 309,
      unusedFilterCount = 81,
      totalFilterCount = 90
    )
    assertNotNull(message)
    assertTrue(message!!.contains("309 type(s)"))
    assertTrue(message.contains("81 of 90"))
    assertTrue(message.contains("--no-sanity-check"))
  }

  @Test
  fun `degraded heuristic stays quiet on a healthy run`() {
    // gateway-impl-check.json: 0 "no longer an API" types, 3 unused filters.
    assertNull(ApiAnalysisSanity.degradedResultMessage(noLongerApiTypeCount = 0, unusedFilterCount = 3, totalFilterCount = 90))
  }

  @Test
  fun `degraded heuristic needs both symptoms at once`() {
    // Many removed API types alone is a legitimate (large) API break.
    assertNull(ApiAnalysisSanity.degradedResultMessage(noLongerApiTypeCount = 50, unusedFilterCount = 2, totalFilterCount = 90))
    // Many unused filters alone happens after a cleanup that fixed the filtered problems.
    assertNull(ApiAnalysisSanity.degradedResultMessage(noLongerApiTypeCount = 0, unusedFilterCount = 90, totalFilterCount = 90))
    // Bundles with few filters cannot produce a meaningful ratio.
    assertNull(
      ApiAnalysisSanity.degradedResultMessage(
        noLongerApiTypeCount = 50,
        unusedFilterCount = ApiAnalysisSanity.MIN_FILTER_RULES - 1,
        totalFilterCount = ApiAnalysisSanity.MIN_FILTER_RULES - 1
      )
    )
    // Exactly at the thresholds it fires.
    assertNotNull(
      ApiAnalysisSanity.degradedResultMessage(
        noLongerApiTypeCount = ApiAnalysisSanity.MIN_NO_LONGER_API_TYPES,
        unusedFilterCount = 5,
        totalFilterCount = 10
      )
    )
  }

  @Test
  fun `missing api packages are exported packages with types that did not resolve as API`() {
    val missing = ApiAnalysisSanity.missingApiPackages(
      exportedApiPackages = setOf("a", "a.webui", "a.webui.entity", "a.stale"),
      packagesWithTypes = setOf("a", "a.webui", "a.webui.entity", "a.internal"),
      packagesResolvedAsApi = setOf("a.webui.entity")
    )
    // "a.stale" is exported but has no types: nothing for PDE to misreport. "a.internal" is not exported.
    assertEquals(setOf("a", "a.webui"), missing)
  }

  @Test
  fun `no missing packages when every exported package resolves as API`() {
    assertTrue(
      ApiAnalysisSanity.missingApiPackages(
        exportedApiPackages = setOf("a", "b"),
        packagesWithTypes = setOf("a", "b", "c"),
        packagesResolvedAsApi = setOf("a", "b")
      ).isEmpty()
    )
  }

  @Test
  fun `batch input defaults keep sanity check on and read older inputs without the new fields`() {
    val path = temp.newFile("batch.json").toPath()
    path.writeText(
      """
      {"currentBundles":[{"currentBundle":{"bundleSymbolicName":"a","path":"/tmp/a.jar"},"outputReportPath":"/tmp/a.json"}]}
      """.trimIndent()
    )
    val input = BatchApiAnalyzerInputJson.read(path)
    assertTrue(input.sanityCheck)
    assertNull(input.failureSummaryPath)

    val written = temp.newFile("batch2.json").toPath()
    written.writeText(BatchApiAnalyzerInputJson.write(input.copy(sanityCheck = false, failureSummaryPath = "/tmp/f.json")))
    val roundTrip = BatchApiAnalyzerInputJson.read(written)
    assertFalse(roundTrip.sanityCheck)
    assertEquals("/tmp/f.json", roundTrip.failureSummaryPath)
  }

  @Test
  fun `failure summary round trips`() {
    val path = temp.newFile("failures.json").toPath()
    val summary = ApiAnalysisFailureSummary(
      listOf(ApiAnalysisFailure("org.knime.gateway.impl", "API description is incomplete; 16 exported package(s) ..."))
    )
    path.writeText(ApiAnalysisFailureSummaryJson.write(summary))
    assertEquals(summary, ApiAnalysisFailureSummaryJson.read(path))
  }
}
