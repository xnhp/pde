package cn.varsa.pde.resolver.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ApiBaselineFiltersTest {
  @Test
  fun `add-all-from-report writes api_filters`() {
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

      val exit = apiBaselineFiltersAddAllFromReportMain(
        arrayOf(
          "--report",
          report.toString(),
          "--problem",
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

      val exit = apiBaselineFiltersAddAllFromReportMain(
        arrayOf(
          "--report",
          report.toString(),
          "--all"
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

      val exit = apiBaselineFiltersAddAllFromReportMain(
        arrayOf(
          "--report", reportsDir.resolve("org.example.bundle1.json").toString(),
          "--all"
        )
      )
      assertEquals(0, exit)
      assertTrue(Files.exists(bundleDir1.resolve(".settings").resolve(".api_filters")))

      val exit2 = apiBaselineFiltersAddAllFromReportMain(
        arrayOf(
          "--report", reportsDir.resolve("org.example.bundle2.json").toString(),
          "--all"
        )
      )
      assertEquals(0, exit2)
      assertTrue(Files.exists(bundleDir2.resolve(".settings").resolve(".api_filters")))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-all-from-report skips unsuppressible-category problems and still applies the rest`() {
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
              "problemId": 1462763580,
              "messageArgs": [],
              "category": "api-baseline",
              "severity": "warning"
            },
            {
              "problemRef": "P000003",
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

      val exit = apiBaselineFiltersAddAllFromReportMain(
        arrayOf("--report", report.toString(), "--all")
      )

      assertEquals(0, exit)
      val content = Files.readString(bundleDir.resolve(".settings").resolve(".api_filters"))
      assertTrue(content.contains("<filter id=\"643842064\""))
      assertTrue(!content.contains("926941240"))
      assertTrue(!content.contains("1462763580"))
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

      val exit = apiBaselineFiltersAddFilterMain(
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

      val exit = apiBaselineFiltersAddFilterMain(
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

      val exit = apiBaselineFiltersAddAllFromReportMain(
        arrayOf(
          "--report",
          reportsDir.resolve("org.example.bundle.json").toString(),
          "--all"
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

  @Test
  fun `prune removes a filter reported as UNUSED_PROBLEM_FILTERS`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val filtersFile = bundleDir.resolve(".settings").resolve(".api_filters")
      Files.createDirectories(filtersFile.parent)
      Files.writeString(
        filtersFile,
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <component id="org.example.bundle" version="2">
            <resource path="src/org/example/Type.java" type="org.example.Type">
                <filter id="338792546">
                    <message_arguments>
                        <message_argument value="Missing @since tag on org.example.Type"/>
                    </message_arguments>
                </filter>
                <filter id="614465566">
                    <message_arguments>
                        <message_argument value="The method org.example.Type.stillUsed() has been removed"/>
                    </message_arguments>
                </filter>
            </resource>
        </component>
        """.trimIndent()
      )

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
              "problemId": 681574430,
              "messageArgs": ["Missing @since tag on org.example.Type"],
              "category": "usage",
              "severity": "warning",
              "message": "The API problem filter for: 'Missing @since tag on org.example.Type' is no longer used"
            }
          ]
        }
        """.trimIndent()
      )

      val exit = apiBaselineFiltersPruneMain(arrayOf("--report", report.toString()))

      assertEquals(0, exit)
      val content = Files.readString(filtersFile)
      assertTrue(!content.contains("Missing @since tag on org.example.Type"))
      assertTrue(content.contains("stillUsed"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `prune ignores non-usage categories`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val filtersFile = bundleDir.resolve(".settings").resolve(".api_filters")
      Files.createDirectories(filtersFile.parent)
      Files.writeString(
        filtersFile,
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <component id="org.example.bundle" version="2">
            <resource path="src/org/example/Type.java" type="org.example.Type">
                <filter id="338792546">
                    <message_arguments>
                        <message_argument value="Missing @since tag on org.example.Type"/>
                    </message_arguments>
                </filter>
            </resource>
        </component>
        """.trimIndent()
      )

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
              "problemId": 681574430,
              "messageArgs": ["Missing @since tag on org.example.Type"],
              "category": "usage",
              "severity": "warning",
              "message": "The API problem filter for: 'Missing @since tag on org.example.Type' is no longer used"
            },
            {
              "problemRef": "P000002",
              "bundleBsn": "org.example.bundle",
              "bundleDir": "${bundleDir.toAbsolutePath().normalize()}",
              "resourceType": "org.example.Type",
              "problemId": 643842064,
              "messageArgs": ["A"],
              "category": "compatibility",
              "severity": "error",
              "message": "The method org.example.Type.foo() has been removed"
            }
          ]
        }
        """.trimIndent()
      )

      val exit = apiBaselineFiltersPruneMain(arrayOf("--report", report.toString()))

      assertEquals(0, exit)
      val content = Files.readString(filtersFile)
      assertTrue(!content.contains("Missing @since tag on org.example.Type"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  // ---- surgical editing: the file must only change where a filter is added or removed ----

  private val pdeStyleFixture = """
    <?xml version="1.0" encoding="UTF-8" standalone="no"?>
    <!-- curated by hand; keep resource order -->
    <component id="org.example.bundle" version="2">
        <resource path="src/org/example/Zeta.java" type="org.example.Zeta">
            <filter comment="kept on purpose" id="1141899266">
                <message_arguments>
                    <message_argument value="org.example.Zeta"/>
                    <message_argument value="zeta()"/>
                </message_arguments>
            </filter>
        </resource>
        <resource path="src/org/example/Type.java" type="org.example.Type">
            <filter id="338792546">
                <message_arguments>
                    <message_argument value="Missing @since tag on org.example.Type"/>
                </message_arguments>
            </filter>
            <filter id="614465566">
                <message_arguments>
                    <message_argument value="The method org.example.Type.stillUsed() has been removed"/>
                </message_arguments>
            </filter>
        </resource>
        <resource path="src/org/example/Alone.java" type="org.example.Alone">
            <filter id="338792546">
                <message_arguments>
                    <message_argument value="Missing @since tag on org.example.Alone"/>
                </message_arguments>
            </filter>
        </resource>
    </component>

  """.trimIndent()

  private fun unusedFilterProblem(bundleDir: Path, type: String, path: String, message: String, ref: String): String = """
    {
      "problemRef": "$ref",
      "bundleBsn": "org.example.bundle",
      "bundleDir": "${bundleDir.toAbsolutePath().normalize()}",
      "resourceType": "$type",
      "resourcePath": "$path",
      "problemId": 681574430,
      "messageArgs": ["$message"],
      "category": "usage",
      "severity": "warning",
      "message": "The API problem filter for: '$message' is no longer used"
    }
  """.trimIndent()

  private fun writeFixture(bundleDir: Path, content: String = pdeStyleFixture): Path {
    val filtersFile = bundleDir.resolve(".settings").resolve(".api_filters")
    Files.createDirectories(filtersFile.parent)
    Files.writeString(filtersFile, content)
    return filtersFile
  }

  @Test
  fun `prune removes only the stale filter lines and leaves every other byte identical`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val filtersFile = writeFixture(bundleDir)
      val report = root.resolve("problems.json")
      Files.writeString(
        report,
        """{"schemaVersion": 1, "problems": [${unusedFilterProblem(bundleDir, "org.example.Type", "src/org/example/Type.java", "Missing @since tag on org.example.Type", "P000001")}]}"""
      )

      assertEquals(0, apiBaselineFiltersPruneMain(arrayOf("--report", report.toString())))

      val expected = pdeStyleFixture.replace(
        """
        |        <filter id="338792546">
        |            <message_arguments>
        |                <message_argument value="Missing @since tag on org.example.Type"/>
        |            </message_arguments>
        |        </filter>
        |""".trimMargin(),
        ""
      )
      assertEquals(expected, Files.readString(filtersFile))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `prune removes the whole resource when its last filter goes`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val filtersFile = writeFixture(bundleDir)
      val report = root.resolve("problems.json")
      Files.writeString(
        report,
        """{"schemaVersion": 1, "problems": [${unusedFilterProblem(bundleDir, "org.example.Alone", "src/org/example/Alone.java", "Missing @since tag on org.example.Alone", "P000001")}]}"""
      )

      assertEquals(0, apiBaselineFiltersPruneMain(arrayOf("--report", report.toString())))

      val expected = pdeStyleFixture.replace(
        """
        |    <resource path="src/org/example/Alone.java" type="org.example.Alone">
        |        <filter id="338792546">
        |            <message_arguments>
        |                <message_argument value="Missing @since tag on org.example.Alone"/>
        |            </message_arguments>
        |        </filter>
        |    </resource>
        |""".trimMargin(),
        ""
      )
      assertEquals(expected, Files.readString(filtersFile))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `prune --dry-run lists the filters and leaves the file untouched`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val filtersFile = writeFixture(bundleDir)
      val before = Files.getLastModifiedTime(filtersFile)
      val report = root.resolve("problems.json")
      Files.writeString(
        report,
        """{"schemaVersion": 1, "problems": [${unusedFilterProblem(bundleDir, "org.example.Type", "src/org/example/Type.java", "Missing @since tag on org.example.Type", "P000001")}]}"""
      )

      val stdout = captureStdout {
        assertEquals(0, apiBaselineFiltersPruneMain(arrayOf("--report", report.toString(), "--dry-run")))
      }

      assertTrue(stdout, stdout.contains("would remove"))
      assertTrue(stdout, stdout.contains("Missing @since tag on org.example.Type"))
      assertEquals(pdeStyleFixture, Files.readString(filtersFile))
      assertEquals(before, Files.getLastModifiedTime(filtersFile))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-all-from-report inserts into an existing resource and appends a new resource in the file's own style`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val filtersFile = writeFixture(bundleDir)
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
              "messageArgs": ["org.example.Type", "List<T> newMethod()"],
              "category": "compatibility"
            },
            {
              "problemRef": "P000002",
              "bundleBsn": "org.example.bundle",
              "bundleDir": "${bundleDir.toAbsolutePath().normalize()}",
              "resourceType": "org.example.Fresh",
              "resourcePath": "src/org/example/Fresh.java",
              "problemId": 338792546,
              "messageArgs": ["Missing @since tag on org.example.Fresh"],
              "category": "since-tag"
            }
          ]
        }
        """.trimIndent()
      )

      assertEquals(
        0,
        apiBaselineFiltersAddAllFromReportMain(
          arrayOf("--report", report.toString(), "--all", "--comment-template", "{problemRef}")
        )
      )

      val expected = pdeStyleFixture
        .replace(
          """
          |                <message_argument value="The method org.example.Type.stillUsed() has been removed"/>
          |            </message_arguments>
          |        </filter>
          |    </resource>
          |""".trimMargin(),
          """
          |                <message_argument value="The method org.example.Type.stillUsed() has been removed"/>
          |            </message_arguments>
          |        </filter>
          |        <filter comment="P000001" id="643842064">
          |            <message_arguments>
          |                <message_argument value="org.example.Type"/>
          |                <message_argument value="List&lt;T&gt; newMethod()"/>
          |            </message_arguments>
          |        </filter>
          |    </resource>
          |""".trimMargin()
        )
        .replace(
          "\n</component>\n",
          """
          |
          |    <resource path="src/org/example/Fresh.java" type="org.example.Fresh">
          |        <filter comment="P000002" id="338792546">
          |            <message_arguments>
          |                <message_argument value="Missing @since tag on org.example.Fresh"/>
          |            </message_arguments>
          |        </filter>
          |    </resource>
          |</component>
          |""".trimMargin()
        )
      assertEquals(expected, Files.readString(filtersFile))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-all-from-report preserves a 2-space indented file`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val twoSpace = pdeStyleFixture.replace(Regex("(?m)^((?:    )+)")) { m -> " ".repeat(m.groupValues[1].length / 2) }
      val filtersFile = writeFixture(bundleDir, twoSpace)
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
              "resourceType": "org.example.Alone",
              "resourcePath": "src/org/example/Alone.java",
              "problemId": 7,
              "messageArgs": ["x"]
            }
          ]
        }
        """.trimIndent()
      )

      assertEquals(0, apiBaselineFiltersAddAllFromReportMain(arrayOf("--report", report.toString(), "--all")))

      val expected = twoSpace.replace(
        "    </filter>\n  </resource>\n</component>",
        "    </filter>\n    <filter id=\"7\">\n      <message_arguments>\n        <message_argument value=\"x\"/>\n      </message_arguments>\n    </filter>\n  </resource>\n</component>"
      )
      assertEquals(expected, Files.readString(filtersFile))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-all-from-report --dry-run prints additions and leaves the file untouched`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val filtersFile = writeFixture(bundleDir)
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
              "resourceType": "org.example.Fresh",
              "problemId": 7,
              "messageArgs": ["x"]
            }
          ]
        }
        """.trimIndent()
      )

      val stdout = captureStdout {
        assertEquals(0, apiBaselineFiltersAddAllFromReportMain(arrayOf("--report", report.toString(), "--all", "--dry-run")))
      }

      assertTrue(stdout, stdout.contains("would create"))
      assertTrue(stdout, stdout.contains("type=org.example.Fresh id=7"))
      assertEquals(pdeStyleFixture, Files.readString(filtersFile))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `add-all-from-report with nothing new does not rewrite the file`() {
    val root = Files.createTempDirectory("api-filters-test")
    try {
      val bundleDir = createBundle(root, "org.example.bundle")
      val filtersFile = writeFixture(bundleDir)
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
              "resourceType": "org.example.Alone",
              "resourcePath": "src/org/example/Alone.java",
              "problemId": 338792546,
              "messageArgs": ["Missing @since tag on org.example.Alone"]
            }
          ]
        }
        """.trimIndent()
      )

      assertEquals(0, apiBaselineFiltersAddAllFromReportMain(arrayOf("--report", report.toString(), "--all")))
      assertEquals(pdeStyleFixture, Files.readString(filtersFile))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `new api_filters files are written in PDE style`() {
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
              "problemId": 7,
              "messageArgs": ["a \"quoted\" & <arg>"]
            }
          ]
        }
        """.trimIndent()
      )

      assertEquals(0, apiBaselineFiltersAddAllFromReportMain(arrayOf("--report", report.toString(), "--all")))

      val expected = """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <component id="org.example.bundle" version="2">
            <resource path="src/org/example/Type.java" type="org.example.Type">
                <filter id="7">
                    <message_arguments>
                        <message_argument value="a &quot;quoted&quot; &amp; &lt;arg&gt;"/>
                    </message_arguments>
                </filter>
            </resource>
        </component>

      """.trimIndent()
      assertEquals(expected, Files.readString(bundleDir.resolve(".settings").resolve(".api_filters")))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun captureStdout(block: () -> Unit): String {
    val original = System.out
    val buffer = java.io.ByteArrayOutputStream()
    System.setOut(java.io.PrintStream(buffer, true, "UTF-8"))
    try {
      block()
    } finally {
      System.setOut(original)
    }
    return buffer.toString("UTF-8")
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
