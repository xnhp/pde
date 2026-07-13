package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.workspace.WorkspaceSetupInputJson
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceSetupCliTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `workspace setup generates valid WorkspaceSetupInput JSON`() {
    val baseDir = tmp.newFolder("cfg").toPath()
    val workspace = tmp.newFolder("workspace").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<EquinoxAppInvocation>()
    val runtimeResolutions = mutableListOf<Path>()
    val exit = workspaceSetupMain(
      args = arrayOf("--config", configFile.toString()),
      equinoxRuntimeResolver = { outputRoot ->
        runtimeResolutions.add(outputRoot)
        fakeEquinoxRuntime(outputRoot)
      },
      equinoxAppRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val invocation = invocations.single()
    assertEquals(WORKSPACE_SETUP_APPLICATION_ID, invocation.applicationId)
    assertEquals(listOf("--input"), invocation.args.subList(0, 1))

    val inputPath = Path.of(invocation.args[1])
    assertTrue(Files.exists(inputPath))
    val input = WorkspaceSetupInputJson.read(inputPath)
    assertTrue(input.projects.isNotEmpty())
    val project = input.projects.first()
    assertEquals("org.example.api", project.bsn)
    assertTrue(project.sourceRoots.isNotEmpty())
    assertNotNull(project.outputDirectory)
    assertEquals(workspace.toAbsolutePath().normalize().toString(), project.bundlePath)
  }

  @Test
  fun `workspace setup uses output-root and data-dir options`() {
    val baseDir = tmp.newFolder("cfg-output").toPath()
    val workspace = tmp.newFolder("workspace-output").toPath()
    val customOutputRoot = tmp.newFolder("custom-output").toPath()
    val customDataDir = tmp.newFolder("custom-data").toPath()
    createProfileWithFramework(baseDir)
    createWorkspaceBundle(workspace)
    val configFile = writeConfigFile(baseDir, workspace)

    val invocations = mutableListOf<EquinoxAppInvocation>()
    val exit = workspaceSetupMain(
      args = arrayOf(
        "--config", configFile.toString(),
        "--output-root", customOutputRoot.toString(),
        "--data-dir", customDataDir.toString()
      ),
      equinoxRuntimeResolver = { fakeEquinoxRuntime(it) },
      equinoxAppRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
    val invocation = invocations.single()

    val inputsDir = customOutputRoot.resolve("inputs")
    assertTrue(Files.isDirectory(inputsDir))

    val inputPath = inputsDir.resolve("workspace-setup.json")
    assertTrue(Files.exists(inputPath))

    val input = WorkspaceSetupInputJson.read(inputPath)
    assertTrue(input.projects.isNotEmpty())

    assertEquals(customDataDir.toAbsolutePath().normalize().toString(), invocation.dataDir)
  }

  @Test
  fun `workspace setup fails without config`() {
    val invocations = mutableListOf<EquinoxAppInvocation>()
    val runtimeResolutions = mutableListOf<Path>()
    val exit = workspaceSetupMain(
      args = arrayOf(),
      equinoxRuntimeResolver = { outputRoot ->
        runtimeResolutions.add(outputRoot)
        fakeEquinoxRuntime(outputRoot)
      },
      equinoxAppRunner = { invocation ->
        invocations += invocation
        0
      }
    )

    assertEquals(2, exit)
    assertEquals(0, runtimeResolutions.size)
    assertEquals(0, invocations.size)
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
    dir.resolve("build.properties").writeText("output.. = bin\n")
    dir.resolve("bin/org/example").createDirectories()
    dir.resolve("bin/org/example/Dummy.class").toFile().writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
  }

  private fun fakeEquinoxRuntime(outputRoot: Path): EquinoxAppRuntime = EquinoxAppRuntime(
    launcherExecutable = Path.of("/fake/equinox-launcher"),
    configurationDir = outputRoot.resolve("configuration"),
    dataDir = outputRoot.resolve("data")
  )
}
