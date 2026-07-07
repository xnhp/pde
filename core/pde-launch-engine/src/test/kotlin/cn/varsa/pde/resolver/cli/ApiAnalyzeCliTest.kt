package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.api.AnalyzerBundleArtifact
import cn.varsa.pde.resolver.api.DirectApiAnalyzerInput
import cn.varsa.pde.resolver.api.DirectApiAnalyzerInputJson
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiAnalyzeCliTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `api analyze wires direct analyzer invocation through injectable runner`() {
    val baseDir = tmp.newFolder("cfg").toPath()
    val workspace = tmp.newFolder("workspace").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
      ),
      launcherResolver = { _, _, _ -> Path.of("/fake/api-analyzer") },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val invocation = invocations.single()
    assertEquals(Path.of("/fake/api-analyzer"), invocation.launcherExecutable)
    assertEquals(DIRECT_API_ANALYZER_APPLICATION_ID, invocation.applicationId)
    assertEquals(baseDir.resolve("api-analyzer").resolve("workspace").toString(), invocation.dataDir)
    assertEquals(listOf("--input", baseDir.resolve("api-analyzer/inputs/org.example.api.json").toString()), invocation.args)
  }

  @Test
  fun `api analyze propagates injected analyzer runner failure`() {
    val baseDir = tmp.newFolder("cfg-failure").toPath()
    val workspace = tmp.newFolder("workspace-failure").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
      ),
      launcherResolver = { _, _, _ -> Path.of("/fake/api-analyzer") },
      analyzerRunner = { invocation ->
        invocations += invocation
        17
      }
    )

    assertEquals(17, exit)
    assertEquals(1, invocations.size)
  }

  @Test
  fun `api analyze direct app writes input manifest for injected runner`() {
    val baseDir = tmp.newFolder("cfg-direct").toPath()
    val workspace = tmp.newFolder("workspace-direct").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
      ),
      launcherResolver = { _, _, _ -> Path.of("/fake/api-analyzer") },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val invocation = invocations.single()
    assertEquals(DIRECT_API_ANALYZER_APPLICATION_ID, invocation.applicationId)
    assertEquals(listOf("--input", baseDir.resolve("api-analyzer/inputs/org.example.api.json").toString()), invocation.args)
    val input = DirectApiAnalyzerInputJson.read(Path.of(invocation.valueAfter("--input")))
    assertEquals("org.example.api", input.currentBundle.bundleSymbolicName)
    assertTrue(input.currentBundle.synthetic)
    assertEquals(baseDir.resolve("api-analyzer/reports/org.example.api.json"), input.outputReportPath)
  }

  @Test
  fun `api analyze direct app uses explicit report path for single bundle`() {
    val baseDir = tmp.newFolder("cfg-direct-report").toPath()
    val workspace = tmp.newFolder("workspace-direct-report").toPath()
    val reportPath = baseDir.resolve("reports").resolve("api-report.json")
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString(),
        "--report", reportPath.toString()
      ),
      launcherResolver = { _, _, _ -> Path.of("/fake/api-analyzer") },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val input = DirectApiAnalyzerInputJson.read(Path.of(invocations.single().valueAfter("--input")))
    assertEquals(reportPath, input.outputReportPath)
  }

  @Test
  fun `api analyze direct app stops before launch when current bundle cannot be materialized`() {
    val baseDir = tmp.newFolder("cfg-direct-missing-output").toPath()
    val workspace = tmp.newFolder("workspace-direct-missing-output").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
      ),
      launcherResolver = { _, _, _ -> Path.of("/fake/api-analyzer") },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(0, invocations.size)
  }

  @Test
  fun `direct analyzer launch plan writes input manifest and invocation args`() {
    val baseDir = tmp.newFolder("direct-plan").toPath()
    val current = baseDir.resolve("current.jar")
    val dependency = baseDir.resolve("dependency.jar")
    val baseline = baseDir.resolve("baseline.jar")
    current.writeText("current")
    dependency.writeText("dependency")
    baseline.writeText("baseline")
    val inputPath = baseDir.resolve("api-analyzer").resolve("input").resolve("org.example.api.json")
    val reportPath = baseDir.resolve("api-analyzer").resolve("reports").resolve("org.example.api.json")

    val plan = writeDirectApiAnalyzerLaunchPlan(
      launcherExecutable = Path.of("/fake/api-analyzer"),
      dataDir = baseDir.resolve("api-analyzer").resolve("workspace").toString(),
      applicationId = "cn.varsa.pde.api_analyzer",
      inputPath = inputPath,
      input = DirectApiAnalyzerInput(
        currentBundle = AnalyzerBundleArtifact("org.example.api", "1.1.0", current),
        dependencyArtifacts = listOf(AnalyzerBundleArtifact("org.example.dep", "1.0.0", dependency)),
        baselineArtifacts = listOf(AnalyzerBundleArtifact("org.example.api", "1.0.0", baseline)),
        outputReportPath = reportPath
      )
    )

    assertEquals(inputPath, plan.inputPath)
    assertEquals(reportPath, plan.outputReportPath)
    assertEquals(listOf("--input", inputPath.toString()), plan.invocation.args)
    assertEquals("cn.varsa.pde.api_analyzer", plan.invocation.applicationId)
    val roundTrip = DirectApiAnalyzerInputJson.read(inputPath)
    assertEquals("org.example.api", roundTrip.currentBundle.bundleSymbolicName)
    assertEquals(reportPath, roundTrip.outputReportPath)
    assertEquals(listOf("org.example.dep"), roundTrip.dependencyArtifacts.map { it.bundleSymbolicName })
    assertEquals(listOf("org.example.api"), roundTrip.baselineArtifacts.map { it.bundleSymbolicName })
  }

  @Test
  fun `direct analyzer input selects current artifact and de-duplicates paths`() {
    val baseDir = tmp.newFolder("direct-input").toPath()
    val current = baseDir.resolve("current.jar")
    val dependency = baseDir.resolve("dependency.jar")
    val duplicateDependency = baseDir.resolve(".").resolve("dependency.jar")
    val baseline = baseDir.resolve("baseline.jar")
    current.writeText("current")
    dependency.writeText("dependency")
    baseline.writeText("baseline")
    val reportPath = baseDir.resolve("reports").resolve("org.example.api.json")
    val filters = baseDir.resolve(".settings").resolve(".api_filters")

    val input = buildDirectApiAnalyzerInput(
      currentBundleSymbolicName = "org.example.api",
      currentArtifacts = listOf(AnalyzerBundleArtifact("org.example.api", "1.1.0", current)),
      dependencyArtifacts = listOf(
        AnalyzerBundleArtifact("org.example.api", "1.1.0", current),
        AnalyzerBundleArtifact("org.example.dep", "1.0.0", dependency),
        AnalyzerBundleArtifact("org.example.dep", "1.0.0", duplicateDependency)
      ),
      baselineArtifacts = listOf(
        AnalyzerBundleArtifact("org.example.api", "1.0.0", baseline),
        AnalyzerBundleArtifact("org.example.api", "1.0.0", baseline.toAbsolutePath().normalize())
      ),
      outputReportPath = reportPath,
      apiFilterFile = filters,
      preferences = mapOf("problem" to "warning")
    )

    assertEquals("org.example.api", input.currentBundle.bundleSymbolicName)
    assertEquals(reportPath, input.outputReportPath)
    assertEquals(filters, input.apiFilterFile)
    assertEquals(mapOf("problem" to "warning"), input.preferences)
    assertEquals(listOf("org.example.dep"), input.dependencyArtifacts.map { it.bundleSymbolicName })
    assertEquals(listOf("org.example.api"), input.baselineArtifacts.map { it.bundleSymbolicName })
  }

  private fun ApiAnalyzerInvocation.valueAfter(option: String): String {
    val index = args.indexOf(option)
    assertTrue(index >= 0, "Missing $option in $args")
    assertTrue(index + 1 < args.size, "Missing value for $option in $args")
    return args[index + 1]
  }

  private fun createWorkspaceBundle(dir: Path, compiledOutput: Boolean = false) {
    val meta = dir.resolve("META-INF").createDirectories()
    meta.resolve("MANIFEST.MF").writeText(
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-Name: Test API Bundle
        Bundle-SymbolicName: org.example.api
        Bundle-Version: 1.0.0
        Bundle-ClassPath: .
      """.trimIndent()
    )
    dir.resolve("src").createDirectories()
    if (compiledOutput) {
      dir.resolve("build.properties").writeText("output.. = bin\n")
      dir.resolve("bin/org/example").createDirectories()
      dir.resolve("bin/org/example/Dummy.class").toFile().writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
    }
  }
}
