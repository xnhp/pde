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
  fun `add-from-report consumes schema v2 fixture report`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val report = root.resolve("problems-v2.json")
      val filterFile = bundleDir.resolve(".settings").resolve(".api_filters")
      Files.writeString(
        report,
        readResource("api-filters/schema-v2-report.json")
          .replace("__API_FILTER_FILE__", filterFile.toAbsolutePath().normalize().toString())
      )

      val exit = apiFiltersMain(
        arrayOf(
          "add-from-report",
          "--report",
          report.toString(),
          "--all",
          "--apply"
        )
      )

      assertEquals(0, exit)
      val content = Files.readString(filterFile)
      assertTrue(content.contains("<component id=\"org.example.bundle\" version=\"2\""))
      assertTrue(content.contains("path=\"src/org/example/Type.java\""))
      assertTrue(content.contains("type=\"org.example.Type\""))
      assertEquals(1, occurrences(content, "<filter id=\"643842064\""))
      val firstArg = content.indexOf("value=\"org.example.Type\"")
      val secondArg = content.indexOf(
        "value=\"public &lt;T extends java.lang.Comparable&lt;T&gt;&gt; T convert(java.util.List&lt;T&gt; values)\""
      )
      val thirdArg = content.indexOf(
        "value=\"java.util.Map&lt;java.lang.String, java.util.List&lt;T&gt;&gt;\""
      )
      assertTrue(firstArg >= 0)
      assertTrue(secondArg > firstArg)
      assertTrue(thirdArg > secondArg)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun readResource(path: String): String =
    javaClass.classLoader.getResource(path)?.readText()
      ?: error("Missing test resource: $path")

  private fun occurrences(text: String, needle: String): Int =
    Regex(Regex.escape(needle)).findAll(text).count()

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
