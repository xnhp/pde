package cn.varsa.pde.resolver.cli

import cn.varsa.pde.cli.support.bundledLibDir
import cn.varsa.pde.cli.support.findJarInDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/** Builds the JaCoCo agent VM argument that records execution data for the launched JVM. */
internal fun jacocoAgentVmArg(agentJar: Path, execFile: Path): String =
  "-javaagent:$agentJar=destfile=$execFile"

/** Per-run execution-data file name: `<kind>-<id>.exec` under [dir] (kind = test|testflow). */
internal fun jacocoExecFile(dir: Path, kind: String, id: String): Path =
  dir.resolve("$kind-$id.exec")

/** The JaCoCo agent VM arg for [agent], recording to a fresh per-run `<kind>-<id>.exec` under [coverageDir]. */
internal fun jacocoAgentVmArgFor(agent: Path, coverageDir: Path, kind: String): String =
  jacocoAgentVmArg(agent, jacocoExecFile(coverageDir, kind, UUID.randomUUID().toString().substring(0, 8)))

/**
 * The `--coverage` directory as an absolute, normalized path. REQUIRED before substituting it into the
 * agent's `destfile=`: a relative path is resolved by the *forked* test/testflow JVM against its own
 * (transient `-data`) working directory, so the `.exec` is written somewhere pde deletes — a silent loss.
 */
internal fun resolveCoverageDir(coverageDir: String): Path = Paths.get(coverageDir).toAbsolutePath().normalize()

/**
 * pde's bundled, Java-21-clean JaCoCo agent jar (`org.jacoco.agent:runtime`), or null when not running
 * from the installed distribution. Preferred over the target's `org.knime.testing/lib/jacocoagent.jar`,
 * which is too old for Java 21 (aborts at premain with `NoSuchFieldException: $jacocoAccess`).
 */
internal fun findBundledJacocoAgent(): Path? = bundledLibDir()?.let { findJarInDir(it, "org.jacoco.agent") }

/**
 * If [coverageDir] is set, returns the JaCoCo agent VM arg recording to a fresh per-run exec file
 * (`<kind>-<id>.exec`) under [coverageDir]; null when coverage is off. The agent is [agentOverride] if
 * given, else pde's bundled agent. Throws [IllegalStateException] if coverage is requested but no agent
 * is available. Each call yields a unique exec file so repeated launches accumulate without clobbering.
 */
fun coverageVmArgOrNull(coverageDir: String?, agentOverride: String?, kind: String): String? {
  if (coverageDir == null) return null
  val covDir = resolveCoverageDir(coverageDir)
  Files.createDirectories(covDir)
  val agent = agentOverride?.let { Paths.get(it) } ?: findBundledJacocoAgent()
  check(agent != null && Files.isRegularFile(agent)) {
    "No JaCoCo agent available (expected a bundled org.jacoco.agent jar in pde's lib/). Pass --jacoco-agent <path>."
  }
  return jacocoAgentVmArgFor(agent, covDir, kind)
}

