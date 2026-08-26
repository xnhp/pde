package cn.varsa.pde.resolver.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Liveness marker for an Eclipse workspace data directory (`-data`).
 *
 * Headless Equinox takes no `.metadata/.lock` (only the IDE's `IDEApplication.checkInstanceLocation`
 * does), so two pde processes opening the same workspace silently corrupt each other's `.metadata`
 * (last SaveManager writer wins). This marker provides the mutual exclusion Eclipse lacks: every
 * pde command that opens a workspace writes `<dataDir>/.pde-live` before launching Equinox and
 * deletes it on exit. `.metadata/.lock` is never touched.
 *
 * File format (one `key=value` per line):
 * ```
 * pid=<pid>
 * start=<ProcessHandle.info().startInstant epoch millis>
 * command=<label>
 * ```
 *
 * Stale rule: a marker is stale when `ProcessHandle.of(pid)` is absent, or present with a different
 * start instant (pid reuse). A stale marker is overwritten; a live one blocks.
 */
data class WorkspaceLiveMarker(val pid: Long, val startMillis: Long?, val command: String) {
  fun isLive(): Boolean {
    val handle = ProcessHandle.of(pid).orElse(null) ?: return false
    val actualStart = handle.info().startInstant().map { it.toEpochMilli() }.orElse(null)
    if (startMillis == null || actualStart == null) return true
    return startMillis == actualStart
  }

  fun serialize(): String = "pid=$pid\nstart=${startMillis ?: ""}\ncommand=$command\n"

  /** Holds the marker for the current process; [close] deletes it. */
  class Lease internal constructor(private val dataDir: Path) : AutoCloseable {
    private val hook = Thread { delete(dataDir) }

    init {
      Runtime.getRuntime().addShutdownHook(hook)
    }

    override fun close() {
      try {
        Runtime.getRuntime().removeShutdownHook(hook)
      } catch (_: IllegalStateException) {
        // JVM already shutting down; the hook deletes the marker.
      }
      delete(dataDir)
    }
  }

  companion object {
    const val FILE_NAME = ".pde-live"

    fun path(dataDir: Path): Path = dataDir.resolve(FILE_NAME)

    fun forCurrentProcess(command: String): WorkspaceLiveMarker {
      val self = ProcessHandle.current()
      return WorkspaceLiveMarker(
        pid = self.pid(),
        startMillis = self.info().startInstant().map { it.toEpochMilli() }.orElse(null),
        command = command
      )
    }

    fun parse(text: String): WorkspaceLiveMarker? {
      val fields = text.lineSequence()
        .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
        .toMap()
      val pid = fields["pid"]?.trim()?.toLongOrNull() ?: return null
      return WorkspaceLiveMarker(
        pid = pid,
        startMillis = fields["start"]?.trim()?.toLongOrNull(),
        command = fields["command"]?.trim().orEmpty()
      )
    }

    /** The marker on disk, or null when absent or unparsable. */
    fun read(dataDir: Path): WorkspaceLiveMarker? {
      val file = path(dataDir)
      if (!Files.isRegularFile(file)) return null
      return runCatching { parse(Files.readString(file)) }.getOrNull()
    }

    /** The marker on disk if it belongs to a process that is still running; null otherwise. */
    fun liveOwner(dataDir: Path): WorkspaceLiveMarker? = read(dataDir)?.takeIf { it.isLive() }

    /** Writes the marker for the current process, overwriting any stale one. */
    fun write(dataDir: Path, command: String): Path {
      Files.createDirectories(dataDir)
      val file = path(dataDir)
      val tmp = dataDir.resolve("$FILE_NAME.tmp")
      Files.writeString(tmp, forCurrentProcess(command).serialize())
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      return file
    }

    fun delete(dataDir: Path) {
      runCatching { Files.deleteIfExists(path(dataDir)) }
    }

    /** Writes the marker and returns a lease whose [Lease.close] deletes it (a shutdown hook is the fallback). */
    fun hold(dataDir: Path, command: String): Lease {
      write(dataDir, command)
      return Lease(dataDir)
    }

    fun inUseMessage(command: String, dataDir: Path, owner: WorkspaceLiveMarker, hint: String): String =
      "$command: workspace ${dataDir.toAbsolutePath().normalize()} is in use by ${owner.command} (pid ${owner.pid}); $hint"

    const val HINT_BUILD_VIA_EDITOR = "build via the editor or stop it"
    const val HINT_OTHER_DATA_DIR = "stop the other process or pass a different --data-dir"
  }
}
