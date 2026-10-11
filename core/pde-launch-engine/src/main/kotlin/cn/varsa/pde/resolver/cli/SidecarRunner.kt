package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.cli.config.SidecarEntry
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.util.regex.Pattern

/**
 * Starts the sidecar processes of a launch preset and stops them when the launched JVM exits.
 *
 * Every output line of a sidecar is written to [out] with a `[<name>] ` prefix. When a sidecar
 * defines a `readyPattern`, the first matching line is logged as `[<name>] ready: <value>`,
 * where value is capture group 1 when the pattern has one, else the whole line.
 */
class SidecarRunner private constructor(
  private val running: List<RunningSidecar>,
  private val out: PrintStream,
  private val closeOut: Boolean
) {
  class RunningSidecar(val entry: SidecarEntry, val process: Process, val reader: Thread)

  fun isAlive(name: String): Boolean = running.firstOrNull { it.entry.name == name }?.process?.isAlive == true

  /** Stops every sidecar: destroy, wait up to [graceMillis], then destroy forcibly. */
  fun stop(graceMillis: Long = DEFAULT_GRACE_MILLIS) {
    for (sidecar in running) {
      val process = sidecar.process
      if (!process.isAlive) continue
      process.descendants().forEach { it.destroy() }
      process.destroy()
      if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(graceMillis, TimeUnit.MILLISECONDS)
      }
      logger.info("[${sidecar.entry.name}] stopped")
    }
    for (sidecar in running) {
      sidecar.reader.join(graceMillis)
    }
    if (closeOut) out.close()
  }

  companion object {
    const val DEFAULT_GRACE_MILLIS = 5_000L
    private val logger: Logger = Logger.getLogger(SidecarRunner::class.java.name)

    /**
     * Starts [entries]. Relative `cwd` values resolve against [baseDir]. The environment of each
     * sidecar is the process environment plus [launchEnv] plus the sidecar's own `env`.
     * Output goes to [logFile] when given (appended, shared with the JVM output), else to [out].
     */
    fun start(
      entries: List<SidecarEntry>,
      baseDir: Path,
      launchEnv: Map<String, String> = emptyMap(),
      logFile: Path? = null,
      out: PrintStream = System.out
    ): SidecarRunner {
      if (entries.isEmpty()) return SidecarRunner(emptyList(), out, closeOut = false)
      val target: PrintStream
      val closeTarget: Boolean
      if (logFile != null) {
        val stream: OutputStream = Files.newOutputStream(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        target = PrintStream(stream, true, StandardCharsets.UTF_8)
        closeTarget = true
      } else {
        target = out
        closeTarget = false
      }
      val started = mutableListOf<RunningSidecar>()
      try {
        for (entry in entries) {
          started.add(startOne(entry, baseDir, launchEnv, target))
        }
      } catch (ex: Exception) {
        SidecarRunner(started, target, closeTarget).stop()
        throw ex
      }
      val runner = SidecarRunner(started, target, closeTarget)
      Runtime.getRuntime().addShutdownHook(Thread { runner.stop() })
      return runner
    }

    private fun startOne(
      entry: SidecarEntry,
      baseDir: Path,
      launchEnv: Map<String, String>,
      out: PrintStream
    ): RunningSidecar {
      require(entry.command.isNotEmpty()) { "Sidecar '${entry.name}' has an empty command" }
      val cwd = entry.cwd?.let { baseDir.resolve(it).toAbsolutePath().normalize() } ?: baseDir
      require(Files.isDirectory(cwd)) { "Sidecar '${entry.name}': cwd does not exist: $cwd" }
      val builder = ProcessBuilder(entry.command)
        .directory(cwd.toFile())
        .redirectErrorStream(true)
      builder.environment().putAll(launchEnv)
      builder.environment().putAll(entry.env)
      logger.info("[${entry.name}] starting: ${entry.command.joinToString(" ")} (cwd $cwd)")
      val process = try {
        builder.start()
      } catch (ex: Exception) {
        error("Sidecar '${entry.name}' failed to start: ${ex.message}")
      }
      val ready = entry.readyPattern?.let { Pattern.compile(it) }
      val reader = Thread({ relay(entry.name, process, ready, out) }, "sidecar-${entry.name}")
      reader.isDaemon = true
      reader.start()
      return RunningSidecar(entry, process, reader)
    }

    private fun relay(name: String, process: Process, ready: Pattern?, out: PrintStream) {
      var readyReported = false
      process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
        for (line in lines) {
          out.println("[$name] $line")
          if (!readyReported && ready != null) {
            val matcher = ready.matcher(line)
            if (matcher.find()) {
              readyReported = true
              val value = if (matcher.groupCount() >= 1) matcher.group(1) ?: line else line
              logger.info("[$name] ready: $value")
            }
          }
        }
      }
      val exit = process.waitFor()
      if (exit != 0) {
        logger.warning("[$name] exited with code $exit")
      }
    }
  }
}
