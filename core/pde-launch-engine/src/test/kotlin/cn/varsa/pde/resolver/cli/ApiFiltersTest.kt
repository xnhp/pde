package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ApiFiltersTest {
  @Test
  fun `add-from-report writes api_filters when apply is set`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val report = root.resolve("problems.json")
      Files.writeString(
        report,
        """
        {
          "schemaVersion": 1,
          "problems": [
            {
              "problemRef": "P000001",
              "bundleBsn": "org.example.bundle",
              "bundleDir": "${bundleDir.toAbsolutePath().normalize()}",
              "resourceType": "org.example.Type",
              "resourcePath": "src/org/example/Type.java",
              "problemId": 643842064,
              "messageArgs": ["A", "B", "C"],
              "severity": "error",
              "category": "baseline"
            }
          ]
        }
        """.trimIndent()
      )

      val exit = apiFiltersMain(
        arrayOf(
          "add-from-report",
          "--report",
          report.toString(),
          "--problem",
          "P000001",
          "--apply"
        )
      )

      assertEquals(0, exit)
      val filtersFile = bundleDir.resolve(".settings").resolve(".api_filters")
      assertTrue(Files.exists(filtersFile))
      val content = Files.readString(filtersFile)
      assertTrue(content.contains("<component id=\"org.example.bundle\" version=\"2\""))
      assertTrue(content.contains("<filter id=\"643842064\""))
      assertTrue(content.contains("<message_argument value=\"A\""))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-from-report is dry-run by default`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val report = root.resolve("problems.json")
      Files.writeString(
        report,
        """
        {
          "schemaVersion": 1,
          "problems": [
            {
              "problemRef": "P000001",
              "bundleBsn": "org.example.bundle",
              "bundleDir": "${bundleDir.toAbsolutePath().normalize()}",
              "resourceType": "org.example.Type",
              "problemId": 643842064,
              "messageArgs": ["A"]
            }
          ]
        }
        """.trimIndent()
      )

      val exit = apiFiltersMain(
        arrayOf(
          "add-from-report",
          "--report",
          report.toString(),
          "--problem",
          "P000001"
        )
      )

      assertEquals(0, exit)
      assertTrue(!Files.exists(bundleDir.resolve(".settings").resolve(".api_filters")))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-from-report consumes structured schema v2 report`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val report = root.resolve("problems-v2.json")
      val filterFile = bundleDir.resolve(".settings").resolve(".api_filters")
      Files.writeString(
        report,
        """
        {
          "schemaVersion": 2,
          "generatedAt": "2026-07-07T00:00:00Z",
          "tool": "pde api-analyze",
          "problems": [
            {
              "problemRef": "P000001",
              "problemId": 643842064,
              "messageArguments": ["A", "B", "C"],
              "problemTypeName": "org.example.Type",
              "resourcePath": "src/org/example/Type.java",
              "severity": "error",
              "line": 12,
              "charStart": 4,
              "charEnd": 18,
              "sourceFile": "Type.java",
              "bundleSymbolicName": "org.example.bundle",
              "baselineComponentId": "org.example.bundle:1.0.0",
              "currentComponentId": "org.example.bundle:2.0.0",
              "apiFilterFile": "${filterFile.toAbsolutePath().normalize()}"
            }
          ]
        }
        """.trimIndent()
      )

      val exit = apiFiltersMain(
        arrayOf(
          "add-from-report",
          "--report",
          report.toString(),
          "--problem",
          "P000001",
          "--apply"
        )
      )

      assertEquals(0, exit)
      val content = Files.readString(filterFile)
      assertTrue(content.contains("<component id=\"org.example.bundle\" version=\"2\""))
      assertTrue(content.contains("path=\"src/org/example/Type.java\""))
      assertTrue(content.contains("type=\"org.example.Type\""))
      assertTrue(content.contains("<message_argument value=\"C\""))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `extractor reads json problem lines from analyzer log`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val log = root.resolve("analyzer.log")
      Files.writeString(
        log,
        """
        [INFO] normal line
        API_PROBLEM_JSON: {"problemId":123,"messageArgs":["x"],"resourceType":"org.example.Type","resourcePath":"src/Type.java"}
        """.trimIndent()
      )

      val extracted = extractApiAnalyzeProblemsFromLog(log, "org.example.bundle", root)
      assertEquals(1, extracted.size)
      assertEquals(123, extracted.first().problemId)
      assertEquals("org.example.Type", extracted.first().resourceType)
      assertEquals(listOf("x"), extracted.first().messageArgs)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun createBundle(root: Path, bsn: String): Path {
    val bundleDir = root.resolve(bsn)
    val metaInf = bundleDir.resolve("META-INF")
    Files.createDirectories(metaInf)
    Files.writeString(
      metaInf.resolve("MANIFEST.MF"),
      """
      Manifest-Version: 1.0
      Bundle-SymbolicName: $bsn

      """.trimIndent()
    )
    return bundleDir
  }
}
