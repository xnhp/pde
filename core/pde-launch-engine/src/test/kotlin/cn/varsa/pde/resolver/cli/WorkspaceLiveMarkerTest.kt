package cn.varsa.pde.resolver.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceLiveMarkerTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun `write then read round-trips pid, start and command`() {
    val dataDir = tmp.newFolder("data").toPath()
    val file = WorkspaceLiveMarker.write(dataDir, "pde jdt-workspace build")

    assertEquals(dataDir.resolve(".pde-live"), file)
    val text = Files.readString(file)
    assertTrue(text.startsWith("pid=${ProcessHandle.current().pid()}\n"), text)
    assertTrue(text.contains("\ncommand=pde jdt-workspace build\n"), text)

    val marker = assertNotNull(WorkspaceLiveMarker.read(dataDir))
    assertEquals(ProcessHandle.current().pid(), marker.pid)
    assertEquals(ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(null), marker.startMillis)
    assertEquals("pde jdt-workspace build", marker.command)
    assertTrue(marker.isLive())
  }

  @Test
  fun `marker of a missing pid is stale`() {
    val dataDir = tmp.newFolder("data").toPath()
    val deadPid = findUnusedPid()
    Files.writeString(dataDir.resolve(".pde-live"), "pid=$deadPid\nstart=1\ncommand=pde lsp run\n")

    assertNotNull(WorkspaceLiveMarker.read(dataDir))
    assertNull(WorkspaceLiveMarker.liveOwner(dataDir))
  }

  @Test
  fun `marker of a live pid with a different start instant is stale`() {
    val dataDir = tmp.newFolder("data").toPath()
    val self = ProcessHandle.current()
    val start = self.info().startInstant().map { it.toEpochMilli() }.orElse(0L)
    Files.writeString(dataDir.resolve(".pde-live"), "pid=${self.pid()}\nstart=${start + 1}\ncommand=pde lsp run\n")

    assertNull(WorkspaceLiveMarker.liveOwner(dataDir))
  }

  @Test
  fun `marker of a live process blocks and reports its command`() {
    val dataDir = tmp.newFolder("data").toPath()
    WorkspaceLiveMarker.write(dataDir, "pde lsp run")

    val owner = assertNotNull(WorkspaceLiveMarker.liveOwner(dataDir))
    assertEquals("pde lsp run", owner.command)
    assertEquals(
      "pde jdt-workspace build: workspace ${dataDir.toAbsolutePath().normalize()} is in use by pde lsp run " +
        "(pid ${ProcessHandle.current().pid()}); build via the editor or stop it",
      WorkspaceLiveMarker.inUseMessage("pde jdt-workspace build", dataDir, owner, WorkspaceLiveMarker.HINT_BUILD_VIA_EDITOR)
    )
  }

  @Test
  fun `hold writes the marker and close deletes it`() {
    val dataDir = tmp.newFolder("data").toPath()
    val lease = WorkspaceLiveMarker.hold(dataDir, "pde jdt-workspace init")
    assertTrue(Files.exists(dataDir.resolve(".pde-live")))
    lease.close()
    assertFalse(Files.exists(dataDir.resolve(".pde-live")))
    assertNull(WorkspaceLiveMarker.read(dataDir))
  }

  @Test
  fun `write overwrites a stale marker`() {
    val dataDir = tmp.newFolder("data").toPath()
    Files.writeString(dataDir.resolve(".pde-live"), "pid=${findUnusedPid()}\nstart=1\ncommand=old\n")
    WorkspaceLiveMarker.write(dataDir, "new")
    assertEquals("new", WorkspaceLiveMarker.read(dataDir)?.command)
  }

  @Test
  fun `unparsable marker reads as absent`() {
    val dataDir = tmp.newFolder("data").toPath()
    Files.writeString(dataDir.resolve(".pde-live"), "garbage")
    assertNull(WorkspaceLiveMarker.read(dataDir))
  }

  private fun findUnusedPid(): Long {
    var pid = 4_000_000L
    while (ProcessHandle.of(pid).isPresent) pid--
    return pid
  }
}
