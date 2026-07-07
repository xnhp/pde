package cn.varsa.pde.resolver.cli

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
  fun `api analyze wires analyzer invocation through injectable runner`() {
    val baseDir = tmp.newFolder("cfg").toPath()
    val workspace = tmp.newFolder("workspace").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<ApiAnalyzerInvocation>()
    val exit = apiAnalyzeMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--baseline-root", baseDir.resolve("target").resolve("p2").toString(),
        "--application", "cn.varsa.pde.api_analyzer"
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
    assertEquals("cn.varsa.pde.api_analyzer", invocation.applicationId)
    assertEquals(baseDir.resolve("api-analyzer").resolve("workspace").toString(), invocation.dataDir)
    assertTrue(invocation.args.contains("-project"), "Expected project arg: ${invocation.args}")
    assertEquals(workspace.toString(), invocation.valueAfter("-project"))
    assertEquals(baseDir.resolve("api-analyzer").resolve("baseline-list.txt").toString(), invocation.valueAfter("-baseline"))
    assertEquals(baseDir.resolve("dependencies-list.txt").toString(), invocation.valueAfter("-dependencyList"))
  }

  private fun ApiAnalyzerInvocation.valueAfter(option: String): String {
    val index = args.indexOf(option)
    assertTrue(index >= 0, "Missing $option in $args")
    assertTrue(index + 1 < args.size, "Missing value for $option in $args")
    return args[index + 1]
  }

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
  }
}
