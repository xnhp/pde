package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.api.BatchApiAnalyzerInputJson
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiBaselineCheckWorkspaceDataTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `api-baseline check --workspace-data sets workspaceProjectName on artifacts`() {
    val baseDir = tmp.newFolder("cfg-wsdata").toPath()
    val workspace = tmp.newFolder("workspace-wsdata").toPath()
    val workspaceDataDir = tmp.newFolder("ws-data").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiBaselineCheckMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString(),
        "--workspace-data", workspaceDataDir.toString()
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
    val input = BatchApiAnalyzerInputJson.read(Path.of(invocation.valueAfter("--input")))
    val bundle = input.currentBundles.single()
    assertEquals("org.example.api", bundle.currentBundle.bundleSymbolicName)
    assertNotNull(bundle.currentBundle.workspaceProjectName)
    assertTrue(bundle.currentBundle.workspaceProjectName!!.startsWith("workspace-wsdata_"))
    assertEquals(workspaceDataDir.toString(), input.workspaceDataDir)
  }

  @Test
  fun `api-baseline check --legacy has null workspaceProjectName`() {
    val baseDir = tmp.newFolder("cfg-no-wsdata").toPath()
    val workspace = tmp.newFolder("workspace-no-wsdata").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiBaselineCheckMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--legacy",
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
    val input = BatchApiAnalyzerInputJson.read(Path.of(invocation.valueAfter("--input")))
    val bundle = input.currentBundles.single()
    assertEquals("org.example.api", bundle.currentBundle.bundleSymbolicName)
    assertNull(bundle.currentBundle.workspaceProjectName)
    assertNull(input.workspaceDataDir)
  }

  @Test
  fun `api-baseline check fails fast without --workspace-data, default workspace data, or --legacy`() {
    val baseDir = tmp.newFolder("cfg-no-fallback").toPath()
    val workspace = tmp.newFolder("workspace-no-fallback").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val runtimeResolutions = mutableListOf<Path>()
    val exit = apiBaselineCheckMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString()
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

  private fun createWorkspaceBundle(dir: Path) {
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
    dir.resolve("build.properties").writeText("output.. = bin\n")
    dir.resolve("bin/org/example").createDirectories()
    dir.resolve("bin/org/example/Dummy.class").toFile().writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
  }
}
