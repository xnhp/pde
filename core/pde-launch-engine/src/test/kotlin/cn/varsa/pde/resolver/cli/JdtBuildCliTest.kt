package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdtBuildCliTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `build refuses a workspace held by another live process without launching Equinox`() {
    val outputRoot = tmp.newFolder("out").toPath()
    val dataDir = outputRoot.resolve("data").also { Files.createDirectories(it) }
    // The current JVM stands in for "another process": same pid/start, different command label.
    val self = ProcessHandle.current()
    val start = self.info().startInstant().map { it.toEpochMilli() }.orElse(0L)
    Files.writeString(dataDir.resolve(".pde-live"), "pid=${self.pid()}\nstart=$start\ncommand=pde lsp run\n")

    var runtimeResolutions = 0
    val invocations = mutableListOf<EquinoxAppInvocation>()
    val exit = jdtBuildMain(
      args = arrayOf("--data", dataDir.toString(), "--output-root", outputRoot.toString()),
      equinoxRuntimeResolver = { root, _ -> runtimeResolutions++; fakeEquinoxRuntime(root) },
      equinoxAppRunner = { invocations += it; 0 }
    )

    assertEquals(2, exit)
    assertTrue(invocations.isEmpty(), "runEquinoxApp must not be invoked")
    assertEquals(0, runtimeResolutions)
    // The other process's marker is left untouched.
    assertEquals("pde lsp run", WorkspaceLiveMarker.read(dataDir)?.command)
  }

  @Test
  fun `build holds the marker while Equinox runs and removes it afterwards`() {
    val outputRoot = tmp.newFolder("out").toPath()
    val dataDir = outputRoot.resolve("data").also { Files.createDirectories(it) }

    var markerDuringRun: WorkspaceLiveMarker? = null
    val exit = jdtBuildMain(
      args = arrayOf("--data", dataDir.toString(), "--output-root", outputRoot.toString()),
      equinoxRuntimeResolver = { root, _ -> fakeEquinoxRuntime(root) },
      equinoxAppRunner = { invocation ->
        assertEquals(dataDir.toString(), invocation.dataDir)
        markerDuringRun = WorkspaceLiveMarker.read(dataDir)
        0
      }
    )

    assertEquals(0, exit)
    assertEquals("pde jdt-workspace build", markerDuringRun?.command)
    assertEquals(ProcessHandle.current().pid(), markerDuringRun?.pid)
    assertFalse(Files.exists(dataDir.resolve(".pde-live")))
  }

  @Test
  fun `build overwrites a stale marker`() {
    val outputRoot = tmp.newFolder("out").toPath()
    val dataDir = outputRoot.resolve("data").also { Files.createDirectories(it) }
    var deadPid = 4_000_000L
    while (ProcessHandle.of(deadPid).isPresent) deadPid--
    Files.writeString(dataDir.resolve(".pde-live"), "pid=$deadPid\nstart=1\ncommand=pde lsp run\n")

    val invocations = mutableListOf<EquinoxAppInvocation>()
    val exit = jdtBuildMain(
      args = arrayOf("--data", dataDir.toString(), "--output-root", outputRoot.toString()),
      equinoxRuntimeResolver = { root, _ -> fakeEquinoxRuntime(root) },
      equinoxAppRunner = { invocations += it; 0 }
    )

    assertEquals(0, exit)
    assertEquals(1, invocations.size)
  }

  private fun fakeEquinoxRuntime(outputRoot: Path): EquinoxAppRuntime = EquinoxAppRuntime(
    launcherExecutable = Path.of("/fake/equinox-launcher"),
    configurationDir = outputRoot.resolve("configuration"),
    dataDir = outputRoot.resolve("data")
  )
}
