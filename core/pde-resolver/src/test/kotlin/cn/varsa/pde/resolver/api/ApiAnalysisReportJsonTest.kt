package cn.varsa.pde.resolver.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiAnalysisReportJsonTest {
  @Test
  fun `writes stable structured report json`() {
    val report = ApiAnalysisReport(
      generatedAt = "2026-07-07T00:00:00Z",
      tool = "pde api-analyze",
      problems = listOf(
        ApiAnalysisProblem(
          problemRef = "P000001",
          problemId = 643842064,
          messageArguments = listOf("A", "B"),
          problemTypeName = "org.example.Type",
          resourcePath = "src/org/example/Type.java",
          severity = "error",
          line = 12,
          charStart = 4,
          charEnd = 18,
          sourceFile = "Type.java",
          bundleSymbolicName = "org.example.bundle",
          baselineComponentId = "org.example.bundle:1.0.0",
          currentComponentId = "org.example.bundle:2.0.0",
          message = "Example API problem",
          category = "baseline",
          apiFilterFile = "/workspace/org.example.bundle/.settings/.api_filters"
        )
      )
    )

    assertEquals(
      """
      {
        "schemaVersion" : 2,
        "generatedAt" : "2026-07-07T00:00:00Z",
        "tool" : "pde api-analyze",
        "problems" : [ {
          "problemRef" : "P000001",
          "problemId" : 643842064,
          "messageArguments" : [ "A", "B" ],
          "problemTypeName" : "org.example.Type",
          "resourcePath" : "src/org/example/Type.java",
          "severity" : "error",
          "line" : 12,
          "charStart" : 4,
          "charEnd" : 18,
          "sourceFile" : "Type.java",
          "bundleSymbolicName" : "org.example.bundle",
          "baselineComponentId" : "org.example.bundle:1.0.0",
          "currentComponentId" : "org.example.bundle:2.0.0",
          "message" : "Example API problem",
          "category" : "baseline",
          "apiFilterFile" : "/workspace/org.example.bundle/.settings/.api_filters"
        } ]
      }
      """.trimIndent(),
      ApiAnalysisReportJson.write(report)
    )
  }
}
