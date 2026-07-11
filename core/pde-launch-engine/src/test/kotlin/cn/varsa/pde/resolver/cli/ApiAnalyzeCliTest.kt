package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.api.AnalyzerBundleArtifact
import cn.varsa.pde.resolver.api.BatchApiAnalyzerInput
import cn.varsa.pde.resolver.api.BatchApiAnalyzerInputJson
import cn.varsa.pde.resolver.api.CurrentBundleInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
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
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val invocation = invocations.single()
    assertEquals(Path.of("/fake/api-analyzer"), invocation.launcherExecutable)
    assertEquals(baseDir.resolve("api-analyzer").resolve("configuration").toString(), invocation.configurationDir)
    assertEquals(DIRECT_API_ANALYZER_APPLICATION_ID, invocation.applicationId)
    assertEquals(baseDir.resolve("api-analyzer").resolve("workspace").toString(), invocation.dataDir)
    assertEquals(listOf("--input", baseDir.resolve("api-analyzer/inputs/batch.json").toString()), invocation.args)
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
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
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
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val invocation = invocations.single()
    assertEquals(DIRECT_API_ANALYZER_APPLICATION_ID, invocation.applicationId)
    assertEquals(listOf("--input", baseDir.resolve("api-analyzer/inputs/batch.json").toString()), invocation.args)
    val input = BatchApiAnalyzerInputJson.read(Path.of(invocation.valueAfter("--input")))
    val bundle = input.currentBundles.single()
    assertEquals("org.example.api", bundle.currentBundle.bundleSymbolicName)
    assertTrue(bundle.currentBundle.synthetic)
    assertEquals(baseDir.resolve("api-analyzer/reports/org.example.api.json"), bundle.outputReportPath)
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
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val input = BatchApiAnalyzerInputJson.read(Path.of(invocations.single().valueAfter("--input")))
    assertEquals(reportPath, input.currentBundles.single().outputReportPath)
  }

  @Test
  fun `api analyze only invokes analyzer for selected workspace bundle`() {
    val baseDir = tmp.newFolder("cfg-direct-bundle").toPath()
    val apiWorkspace = tmp.newFolder("workspace-direct-bundle-api").toPath()
    val otherWorkspace = tmp.newFolder("workspace-direct-bundle-other").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(apiWorkspace, compiledOutput = true, bsn = "org.example.api")
    createWorkspaceBundle(otherWorkspace, compiledOutput = true, bsn = "org.example.other")
    val configFile = writeMultiBundleConfigFile(baseDir, apiWorkspace, otherWorkspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString(),
        "--bundle", "org.example.other"
      ),
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val input = BatchApiAnalyzerInputJson.read(Path.of(invocations.single().valueAfter("--input")))
    assertEquals("org.example.other", input.currentBundles.single().currentBundle.bundleSymbolicName)
  }

  @Test
  fun `api analyze direct app keeps target and baseline directory artifacts and plans baseline once`() {
    val baseDir = tmp.newFolder("cfg-direct-directory-artifacts").toPath()
    val apiWorkspace = tmp.newFolder("workspace-direct-directory-artifacts-api").toPath()
    val otherWorkspace = tmp.newFolder("workspace-direct-directory-artifacts-other").toPath()
    createProfileWithFramework(baseDir)
    val targetDependency = createTargetBundleDirectory(baseDir, "org.example.dep")
    val apiBaseline = createTargetBundleDirectory(baseDir, "org.example.api", version = "0.9.0")
    createTargetBundleDirectory(baseDir, "org.example.other", version = "0.9.0")
    rewriteProfileArtifacts(baseDir)
    createWorkspaceBundle(apiWorkspace, compiledOutput = true, bsn = "org.example.api")
    createWorkspaceBundle(otherWorkspace, compiledOutput = true, bsn = "org.example.other")
    val configFile = writeMultiBundleConfigFile(baseDir, apiWorkspace, otherWorkspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
      ),
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    // A 2-bundle selection must still launch exactly ONE analyzer JVM: both bundles are folded
    // into a single BatchApiAnalyzerInput sharing one materialized baseline artifact set.
    assertEquals(1, invocations.size)
    val batchInput = BatchApiAnalyzerInputJson.read(Path.of(invocations.single().valueAfter("--input")))
    val bundles = batchInput.currentBundles.sortedBy { it.currentBundle.bundleSymbolicName }
    assertEquals(
      listOf("org.example.api", "org.example.other"),
      bundles.map { it.currentBundle.bundleSymbolicName }
    )
    assertTrue(
      batchInput.baselineArtifacts.any { artifact ->
        artifact.bundleSymbolicName == "org.example.dep" &&
          artifact.path.toAbsolutePath().normalize() == targetDependency.toAbsolutePath().normalize() &&
          !artifact.synthetic
      },
      "Expected target baseline directory in ${batchInput.baselineArtifacts}"
    )
    assertTrue(batchInput.baselineArtifacts.any { artifact ->
      artifact.bundleSymbolicName == "org.example.api" && artifact.path == apiBaseline && !artifact.synthetic
    })
    assertTrue(Files.isDirectory(targetDependency))
    assertTrue(Files.isDirectory(apiBaseline))
    assertEquals(baseDir.resolve("api-analyzer/reports/org.example.api.json"), bundles.first().outputReportPath)
    assertEquals(baseDir.resolve("api-analyzer/reports/org.example.other.json"), bundles.last().outputReportPath)
    assertTrue(!Files.exists(baseDir.resolve("api-analyzer/synthetic-artifacts/baseline/org.example.api")))
    assertTrue(!Files.exists(baseDir.resolve("api-analyzer/synthetic-artifacts/baseline/org.example.other")))
  }

  @Test
  fun `api analyze includes every target version needed by disjoint Require-Bundle ranges`() {
    // Regression test for the single-version-per-BSN gap: LaunchPlanner-style selection (used by
    // pde run/pde compile) picks exactly ONE artifact per bundle-symbolic-name across the whole
    // workspace, which silently drops the version(s) needed by requirers with non-overlapping
    // Require-Bundle ranges on that same BSN. Here two target-platform bundles that both end up in
    // the analyzer's dependency set (org.example.needsold and org.example.needsnew) Require-Bundle
    // disjoint ranges of org.example.lib -- [1.0.0,1.5.0) and [2.0.0,3.0.0) -- and only one of those
    // two org.example.lib versions is present in the target platform's LaunchPlanner selection.
    // Both must still end up in the materialized dependency artifact set for API analysis.
    val baseDir = tmp.newFolder("cfg-disjoint-require-ranges").toPath()
    val workspace = tmp.newFolder("workspace-disjoint-require-ranges").toPath()
    createProfileWithFramework(baseDir)
    createTargetBundleDirectory(baseDir, "org.example.lib", version = "1.0.0")
    createTargetBundleDirectory(baseDir, "org.example.lib", version = "2.0.0")
    createTargetBundleDirectory(
      baseDir,
      "org.example.needsold",
      requireBundle = "org.example.lib;bundle-version=\"[1.0.0,1.5.0)\""
    )
    createTargetBundleDirectory(
      baseDir,
      "org.example.needsnew",
      requireBundle = "org.example.lib;bundle-version=\"[2.0.0,3.0.0)\""
    )
    rewriteProfileArtifacts(baseDir)
    createWorkspaceBundle(
      workspace,
      compiledOutput = true,
      requireBundle = "org.example.needsold,org.example.needsnew"
    )
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
      ),
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val input = BatchApiAnalyzerInputJson.read(Path.of(invocations.single().valueAfter("--input")))
    val libVersions = input.dependencyArtifacts
      .filter { it.bundleSymbolicName == "org.example.lib" }
      .map { it.version }
      .toSet()
    assertEquals(
      setOf("1.0.0", "2.0.0"),
      libVersions,
      "Expected both org.example.lib versions in ${input.dependencyArtifacts}"
    )
  }

  @Test
  fun `api analyze does not add extra target versions when a single version satisfies every requirer`() {
    // Regression guard for the fix above: when there is no conflicting Require-Bundle range, the
    // augmentation must not blow up the dependency set with redundant/unneeded extra versions --
    // the common case (single version per BSN) must stay exactly as before.
    val baseDir = tmp.newFolder("cfg-single-require-range").toPath()
    val workspace = tmp.newFolder("workspace-single-require-range").toPath()
    createProfileWithFramework(baseDir)
    createTargetBundleDirectory(baseDir, "org.example.lib", version = "1.0.0")
    createTargetBundleDirectory(
      baseDir,
      "org.example.needsold",
      requireBundle = "org.example.lib;bundle-version=\"[1.0.0,1.5.0)\""
    )
    rewriteProfileArtifacts(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true, requireBundle = "org.example.needsold")
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
      ),
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val input = BatchApiAnalyzerInputJson.read(Path.of(invocations.single().valueAfter("--input")))
    val libArtifacts = input.dependencyArtifacts.filter { it.bundleSymbolicName == "org.example.lib" }
    assertEquals(1, libArtifacts.size, "Expected exactly one org.example.lib artifact in ${input.dependencyArtifacts}")
    assertEquals("1.0.0", libArtifacts.single().version)
  }

  @Test
  fun `api analyze fails before launch when selected workspace bundle is missing`() {
    val baseDir = tmp.newFolder("cfg-direct-bundle-missing").toPath()
    val workspace = tmp.newFolder("workspace-direct-bundle-missing").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val runtimeResolutions = mutableListOf<Path>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString(),
        "--bundle", "org.example.missing"
      ),
      analyzerRuntimeResolver = { outputRoot ->
        runtimeResolutions.add(outputRoot)
        fakeAnalyzerRuntime(outputRoot)
      },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(0, runtimeResolutions.size)
    assertEquals(0, invocations.size)
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
      analyzerRuntimeResolver = { outputRoot -> fakeAnalyzerRuntime(outputRoot) },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(0, invocations.size)
  }

  @Test
  fun `api analyze fails before launch when packaged analyzer runtime is missing`() {
    val baseDir = tmp.newFolder("cfg-missing-runtime").toPath()
    val workspace = tmp.newFolder("workspace-missing-runtime").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true)
    val configFile = writeConfigFile(baseDir, workspace)
    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val runtimeResolutions = mutableListOf<Path>()

    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
      ),
      analyzerRuntimeResolver = { outputRoot ->
        runtimeResolutions.add(outputRoot)
        null
      },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(listOf(baseDir.resolve("api-analyzer")), runtimeResolutions)
    assertEquals(0, invocations.size)
  }

  @Test
  fun `api analyze fails before launch when config is missing`() {
    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val runtimeResolutions = mutableListOf<Path>()

    val exit = apiAnalyzeMain(
      args = arrayOf("--config", tmp.root.toPath().resolve("missing.yaml").toString()),
      analyzerRuntimeResolver = { outputRoot ->
        runtimeResolutions.add(outputRoot)
        fakeAnalyzerRuntime(outputRoot)
      },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(0, runtimeResolutions.size)
    assertEquals(0, invocations.size)
  }

  @Test
  fun `api analyze fails before launch when target profile is missing`() {
    val baseDir = tmp.newFolder("cfg-missing-profile").toPath()
    val workspace = tmp.newFolder("workspace-missing-profile").toPath()
    createWorkspaceBundle(workspace, compiledOutput = true)
    val configFile = writeConfigFile(baseDir, workspace)
    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val runtimeResolutions = mutableListOf<Path>()

    val exit = apiAnalyzeMain(
      args = arrayOf("--config", configFile.toString()),
      analyzerRuntimeResolver = { outputRoot ->
        runtimeResolutions.add(outputRoot)
        fakeAnalyzerRuntime(outputRoot)
      },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(0, runtimeResolutions.size)
    assertEquals(0, invocations.size)
  }

  @Test
  fun `api analyze fails before launch when baseline root is missing`() {
    val baseDir = tmp.newFolder("cfg-missing-baseline").toPath()
    val workspace = tmp.newFolder("workspace-missing-baseline").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true)
    val configFile = writeConfigFile(baseDir, workspace)
    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val runtimeResolutions = mutableListOf<Path>()

    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("missing-baseline").toString()
      ),
      analyzerRuntimeResolver = { outputRoot ->
        runtimeResolutions.add(outputRoot)
        fakeAnalyzerRuntime(outputRoot)
      },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(0, runtimeResolutions.size)
    assertEquals(0, invocations.size)
  }

  @Test
  fun `api analyze fails before launch when target baseline is not provisioned`() {
    val baseDir = tmp.newFolder("cfg-target-baseline").toPath()
    val workspace = tmp.newFolder("workspace-target-baseline").toPath()
    val baselineTarget = baseDir.resolve("API-Baseline.target")
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace, compiledOutput = true)
    baselineTarget.writeText("<target name=\"API Baseline\"/>")
    val configFile = writeConfigFile(baseDir, workspace)
    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val runtimeResolutions = mutableListOf<Path>()

    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baselineTarget.toString()
      ),
      analyzerRuntimeResolver = { outputRoot ->
        runtimeResolutions.add(outputRoot)
        fakeAnalyzerRuntime(outputRoot)
      },
      analyzerRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(0, runtimeResolutions.size)
    assertEquals(0, invocations.size)
  }

  @Test
  fun `batch analyzer launch plan writes input manifest and invocation args`() {
    val baseDir = tmp.newFolder("batch-plan").toPath()
    val current = baseDir.resolve("current.jar")
    val dependency = baseDir.resolve("dependency.jar")
    val baseline = baseDir.resolve("baseline.jar")
    current.writeText("current")
    dependency.writeText("dependency")
    baseline.writeText("baseline")
    val inputPath = baseDir.resolve("api-analyzer").resolve("input").resolve("batch.json")
    val reportPath = baseDir.resolve("api-analyzer").resolve("reports").resolve("org.example.api.json")

    val plan = writeBatchApiAnalyzerLaunchPlan(
      launcherExecutable = Path.of("/fake/api-analyzer"),
      configurationDir = baseDir.resolve("api-analyzer").resolve("configuration").toString(),
      dataDir = baseDir.resolve("api-analyzer").resolve("workspace").toString(),
      applicationId = "cn.varsa.pde.api_analyzer",
      inputPath = inputPath,
      input = BatchApiAnalyzerInput(
        currentBundles = listOf(
          CurrentBundleInfo(
            currentBundle = AnalyzerBundleArtifact("org.example.api", "1.1.0", current),
            outputReportPath = reportPath
          )
        ),
        dependencyArtifacts = listOf(AnalyzerBundleArtifact("org.example.dep", "1.0.0", dependency)),
        baselineArtifacts = listOf(AnalyzerBundleArtifact("org.example.api", "1.0.0", baseline))
      )
    )

    assertEquals(inputPath, plan.inputPath)
    assertEquals(listOf(reportPath), plan.outputReportPaths)
    assertEquals(listOf("--input", inputPath.toString()), plan.invocation.args)
    assertEquals("cn.varsa.pde.api_analyzer", plan.invocation.applicationId)
    val roundTrip = BatchApiAnalyzerInputJson.read(inputPath)
    assertEquals("org.example.api", roundTrip.currentBundles.single().currentBundle.bundleSymbolicName)
    assertEquals(reportPath, roundTrip.currentBundles.single().outputReportPath)
    assertEquals(listOf("org.example.dep"), roundTrip.dependencyArtifacts.map { it.bundleSymbolicName })
    assertEquals(listOf("org.example.api"), roundTrip.baselineArtifacts.map { it.bundleSymbolicName })
  }

  @Test
  fun `direct analyzer command launches equinox launcher jar with java`() {
    val command = buildApiAnalyzerCommand(
      ApiAnalyzerInvocation(
        launcherExecutable = Path.of("/runtime/plugins/org.eclipse.equinox.launcher.jar"),
        configurationDir = "/runtime/configuration",
        dataDir = "/runtime/workspace",
        applicationId = "cn.varsa.pde.api_analyzer",
        args = listOf("--input", "/inputs/org.example.api.json"),
        logFile = null
      ),
      javaBin = "/java/bin/java"
    )

    assertEquals(
      listOf(
        "/java/bin/java",
        "-jar",
        "/runtime/plugins/org.eclipse.equinox.launcher.jar",
        "-nosplash",
        "-consoleLog",
        "-configuration",
        "/runtime/configuration",
        "-data",
        "/runtime/workspace",
        "-application",
        "cn.varsa.pde.api_analyzer",
        "--input",
        "/inputs/org.example.api.json"
      ),
      command
    )
  }

  @Test
  fun `resolved analyzer bundle input selects current artifact and de-duplicates paths`() {
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

    val input = resolveAnalyzerBundleInput(
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
      apiFilterFile = filters
    )

    assertEquals("org.example.api", input.currentBundle.bundleSymbolicName)
    assertEquals(reportPath, input.outputReportPath)
    assertEquals(filters, input.apiFilterFile)
    assertEquals(listOf("org.example.dep"), input.dependencyArtifacts.map { it.bundleSymbolicName })
    assertEquals(listOf("org.example.api"), input.baselineArtifacts.map { it.bundleSymbolicName })
  }

  private fun ApiAnalyzerInvocation.valueAfter(option: String): String {
    val index = args.indexOf(option)
    assertTrue(index >= 0, "Missing $option in $args")
    assertTrue(index + 1 < args.size, "Missing value for $option in $args")
    return args[index + 1]
  }

  private fun fakeAnalyzerRuntime(outputRoot: Path): ApiAnalyzerRuntime = ApiAnalyzerRuntime(
    launcherExecutable = Path.of("/fake/api-analyzer"),
    configurationDir = outputRoot.resolve("configuration"),
    dataDir = outputRoot.resolve("workspace")
  )

  private fun createWorkspaceBundle(
    dir: Path,
    compiledOutput: Boolean = false,
    bsn: String = "org.example.api",
    requireBundle: String? = null
  ) {
    val meta = dir.resolve("META-INF").createDirectories()
    meta.resolve("MANIFEST.MF").writeText(buildString {
      appendLine("Manifest-Version: 1.0")
      appendLine("Bundle-ManifestVersion: 2")
      appendLine("Bundle-Name: Test API Bundle")
      appendLine("Bundle-SymbolicName: $bsn")
      appendLine("Bundle-Version: 1.0.0")
      appendLine("Bundle-ClassPath: .")
      if (requireBundle != null) appendLine("Require-Bundle: $requireBundle")
    })
    dir.resolve("src").createDirectories()
    if (compiledOutput) {
      dir.resolve("build.properties").writeText("output.. = bin\n")
      dir.resolve("bin/org/example").createDirectories()
      dir.resolve("bin/org/example/Dummy.class").toFile().writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
    }
  }

  private fun createTargetBundleDirectory(
    baseDir: Path,
    bsn: String,
    version: String = "1.0.0",
    requireBundle: String? = null
  ): Path {
    val bundleDir = baseDir.resolve("target").resolve("p2").resolve("bundle-pool")
      .resolve("plugins").resolve("${bsn}_$version").createDirectories()
    bundleDir.resolve("META-INF").createDirectories().resolve("MANIFEST.MF").writeText(
      buildString {
        appendLine("Manifest-Version: 1.0")
        appendLine("Bundle-ManifestVersion: 2")
        appendLine("Bundle-Name: $bsn")
        appendLine("Bundle-SymbolicName: $bsn")
        appendLine("Bundle-Version: $version")
        if (requireBundle != null) appendLine("Require-Bundle: $requireBundle")
      }
    )
    return bundleDir
  }

  private fun rewriteProfileArtifacts(baseDir: Path) {
    val p2Root = baseDir.resolve("target").resolve("p2")
    val pool = p2Root.resolve("bundle-pool")
    val plugins = pool.resolve("plugins")
    val artifacts = Files.list(plugins).use { stream ->
      stream.iterator().asSequence()
        .filterNot { Files.isHidden(it) }
        .mapNotNull { plugin ->
          val name = plugin.fileName.toString().removeSuffix(".jar")
          val separator = name.lastIndexOf('_')
          if (separator <= 0) null else name.substring(0, separator) to name.substring(separator + 1)
        }
        .toList()
    }
    val profileFile = p2Root.resolve("org.eclipse.equinox.p2.engine")
      .resolve("profileRegistry")
      .resolve("profile.Profile")
      .resolve("1.profile")
    profileFile.writeText(
      buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<profile id=\"profile\" timestamp=\"1\" version=\"1.0.0\">")
        appendLine("  <properties>")
        appendLine("    <property name=\"org.eclipse.equinox.p2.cache\" value=\"${pool.toUri()}\"/>")
        appendLine("  </properties>")
        appendLine("  <artifacts size=\"${artifacts.size}\">")
        artifacts.forEach { (id, version) ->
          appendLine("    <artifact classifier=\"osgi.bundle\" id=\"$id\" version=\"$version\"/>")
        }
        appendLine("  </artifacts>")
        appendLine("</profile>")
      }
    )
  }

  private fun writeMultiBundleConfigFile(baseDir: Path, vararg workspaces: Path): Path {
    val configFile = baseDir.resolve("pde.yaml")
    configFile.writeText(
      buildString {
        appendLine("target:")
        appendLine("  profileId: profile")
        appendLine("  p2Path: target/p2")
        appendLine("bundles:")
        workspaces.forEach { workspace ->
          appendLine("  - path: ${workspace.toAbsolutePath()}")
        }
      }
    )
    return configFile
  }
}
