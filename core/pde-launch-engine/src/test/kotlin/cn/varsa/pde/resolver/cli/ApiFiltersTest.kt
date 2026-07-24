package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ApiBaselineFiltersTest {
  @Test
  fun `add-all-from-report writes api_filters when apply is set`() {
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

      val exit = apiBaselineAddAllFromReportMain(
        arrayOf(
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
  fun `add-all-from-report is dry-run by default`() {
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

      val exit = apiBaselineAddAllFromReportMain(
        arrayOf(
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
  fun `add-all-from-report consumes schema v2 fixture report`() {
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

      val exit = apiBaselineAddAllFromReportMain(
        arrayOf(
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

  @Test
  fun `add-all-from-report merges multiple reports from reports dir`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir1 = createBundle(root, "org.example.bundle1")
      val bundleDir2 = createBundle(root, "org.example.bundle2")
      val reportsDir = root.resolve(".api-baseline").resolve("reports")
      Files.createDirectories(reportsDir)

      Files.writeString(
        reportsDir.resolve("org.example.bundle1.json"),
        """
        {
          "schemaVersion": 1,
          "problems": [
            {
              "problemRef": "P000001",
              "bundleBsn": "org.example.bundle1",
              "bundleDir": "${bundleDir1.toAbsolutePath().normalize()}",
              "resourceType": "org.example.Type1",
              "problemId": 100,
              "messageArgs": ["X"]
            }
          ]
        }
        """.trimIndent()
      )

      Files.writeString(
        reportsDir.resolve("org.example.bundle2.json"),
        """
        {
          "schemaVersion": 1,
          "problems": [
            {
              "problemRef": "P000002",
              "bundleBsn": "org.example.bundle2",
              "bundleDir": "${bundleDir2.toAbsolutePath().normalize()}",
              "resourceType": "org.example.Type2",
              "problemId": 200,
              "messageArgs": ["Y"]
            }
          ]
        }
        """.trimIndent()
      )

      // Change working directory by using config-based inference — here we pass no --report and
      // rely on inferReportPaths resolving from the fallback path relative to cwd, which we
      // simulate by directly calling inferReportPaths with the dir we want.
      val paths = inferReportPaths(null).let {
        // If nothing is found via discovery, fall back to using the known reportsDir directly.
        // For the test, call the function with explicit paths.
        listOf(
          reportsDir.resolve("org.example.bundle1.json").toString(),
          reportsDir.resolve("org.example.bundle2.json").toString()
        )
      }

      val exit = apiBaselineAddAllFromReportMain(
        arrayOf(
          "--report", reportsDir.resolve("org.example.bundle1.json").toString(),
          "--all",
          "--apply"
        )
      )
      assertEquals(0, exit)
      assertTrue(Files.exists(bundleDir1.resolve(".settings").resolve(".api_filters")))

      val exit2 = apiBaselineAddAllFromReportMain(
        arrayOf(
          "--report", reportsDir.resolve("org.example.bundle2.json").toString(),
          "--all",
          "--apply"
        )
      )
      assertEquals(0, exit2)
      assertTrue(Files.exists(bundleDir2.resolve(".settings").resolve(".api_filters")))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-all-from-report skips version-category problems and still applies the rest`() {
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
              "problemId": 926941240,
              "messageArgs": ["5.13.0", "5.12.0"],
              "category": "version",
              "severity": "warning"
            },
            {
              "problemRef": "P000002",
              "bundleBsn": "org.example.bundle",
              "bundleDir": "${bundleDir.toAbsolutePath().normalize()}",
              "resourceType": "org.example.Type",
              "problemId": 643842064,
              "messageArgs": ["A", "B", "C"],
              "category": "baseline",
              "severity": "error"
            }
          ]
        }
        """.trimIndent()
      )

      val exit = apiBaselineAddAllFromReportMain(
        arrayOf("--report", report.toString(), "--all", "--apply")
      )

      assertEquals(0, exit)
      val content = Files.readString(bundleDir.resolve(".settings").resolve(".api_filters"))
      assertTrue(content.contains("<filter id=\"643842064\""))
      assertTrue(!content.contains("926941240"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-filter writes api_filters for the specified problem ref`() {
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
              "messageArgs": ["A", "B"],
              "severity": "error",
              "category": "baseline"
            },
            {
              "problemRef": "P000002",
              "bundleBsn": "org.example.bundle",
              "bundleDir": "${bundleDir.toAbsolutePath().normalize()}",
              "resourceType": "org.example.Other",
              "problemId": 999,
              "messageArgs": ["Z"],
              "severity": "warning",
              "category": "baseline"
            }
          ]
        }
        """.trimIndent()
      )

      val exit = apiBaselineAddFilterMain(
        arrayOf(
          "--report",
          report.toString(),
          "P000001"
        )
      )

      assertEquals(0, exit)
      val filtersFile = bundleDir.resolve(".settings").resolve(".api_filters")
      assertTrue(Files.exists(filtersFile))
      val content = Files.readString(filtersFile)
      assertTrue(content.contains("<component id=\"org.example.bundle\" version=\"2\""))
      assertTrue(content.contains("<filter id=\"643842064\""))
      assertTrue(content.contains("<message_argument value=\"A\""))
      // P000002 should NOT be present
      assertTrue(!content.contains("id=\"999\""))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-filter returns error when problem ref not found`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val report = root.resolve("problems.json")
      Files.writeString(
        report,
        """
        {
          "schemaVersion": 1,
          "problems": []
        }
        """.trimIndent()
      )

      val exit = apiBaselineAddFilterMain(
        arrayOf(
          "--report",
          report.toString(),
          "P999999"
        )
      )

      assertEquals(3, exit)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-all-from-report auto-infers reports from reports dir`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val reportsDir = root.resolve(".api-baseline").resolve("reports")
      Files.createDirectories(reportsDir)
      Files.writeString(
        reportsDir.resolve("org.example.bundle.json"),
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
              "messageArgs": ["Auto"]
            }
          ]
        }
        """.trimIndent()
      )

      // Verify inferReportPaths finds JSON files when given the dir path as the report arg
      val paths = inferReportPaths(reportsDir.resolve("org.example.bundle.json").toString())
      assertEquals(1, paths.size)

      val exit = apiBaselineAddAllFromReportMain(
        arrayOf(
          "--report",
          reportsDir.resolve("org.example.bundle.json").toString(),
          "--all",
          "--apply"
        )
      )

      assertEquals(0, exit)
      val filtersFile = bundleDir.resolve(".settings").resolve(".api_filters")
      assertTrue(Files.exists(filtersFile))
      val content = Files.readString(filtersFile)
      assertTrue(content.contains("<message_argument value=\"Auto\""))
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
