package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.index.ResolvedBundle as TargetResolvedBundle
import java.nio.file.Path
import java.util.Properties

data class CompileClasspathEnvironment(
  val moduleRoot: Path,
  val buildProperties: Properties,
  val targetIndex: TargetPlatformIndex,
  val workspaceBundles: Map<String, WorkspaceBundleDescriptor>
)

data class CompileClasspathResult(
  val entries: List<CompileClasspathEntry>,
  val workspaceDependencies: Set<String>,
  val problems: List<CompileClasspathProblem>
)

sealed interface CompileClasspathEntry {
  data class ModulePath(val path: Path) : CompileClasspathEntry
  data class TargetBundle(val bundle: TargetResolvedBundle, val entryPath: String?) : CompileClasspathEntry
  data class WorkspaceResource(val descriptor: WorkspaceBundleDescriptor, val entryPath: String) : CompileClasspathEntry
}

data class CompileClasspathProblem(
  val rawEntry: String,
  val type: CompileClasspathProblemType,
  val message: String
)

enum class CompileClasspathProblemType { MISSING_BUNDLE, MALFORMED_ENTRY }

object CompileClasspathResolver {
  private const val JARS_EXTRA_CLASSPATH = "jars.extra.classpath"

  fun resolve(environment: CompileClasspathEnvironment): CompileClasspathResult {
    val raw = environment.buildProperties.getProperty(JARS_EXTRA_CLASSPATH)
      ?: return CompileClasspathResult(emptyList(), emptySet(), emptyList())

    val entries = mutableListOf<CompileClasspathEntry>()
    val workspaceDeps = linkedSetOf<String>()
    val problems = mutableListOf<CompileClasspathProblem>()
    val workspaceByBsn = environment.workspaceBundles

    raw.split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .forEach { token ->
        if (!token.startsWith("platform:", ignoreCase = true)) {
          val path = environment.moduleRoot.resolve(token).normalize()
          entries += CompileClasspathEntry.ModulePath(path)
        } else {
          handlePlatformEntry(token, environment, workspaceByBsn, entries, workspaceDeps, problems)
        }
      }

    return CompileClasspathResult(entries, workspaceDeps, problems)
  }

  private fun handlePlatformEntry(
    token: String,
    env: CompileClasspathEnvironment,
    workspaceByBsn: Map<String, WorkspaceBundleDescriptor>,
    entries: MutableList<CompileClasspathEntry>,
    workspaceDeps: MutableSet<String>,
    problems: MutableList<CompileClasspathProblem>
  ) {
    val body = token.removePrefix("platform:").removePrefix("/")
    val segments = body.split('/').filter { it.isNotEmpty() }
    if (segments.size < 2) {
      problems += CompileClasspathProblem(token, CompileClasspathProblemType.MALFORMED_ENTRY, "Expected platform:/plugin/<bsn>")
      return
    }
    val kind = segments[0].lowercase()
    if (kind != "plugin" && kind != "fragment") {
      problems += CompileClasspathProblem(token, CompileClasspathProblemType.MALFORMED_ENTRY, "Unsupported platform type '$kind'")
      return
    }
    val bsn = segments[1]
    val entryPath = segments.drop(2).takeIf { it.isNotEmpty() }?.joinToString("/")

    val targetBundle = env.targetIndex.get(bsn)
    if (targetBundle != null) {
      entries += CompileClasspathEntry.TargetBundle(targetBundle, entryPath)
      return
    }

    val workspace = workspaceByBsn[bsn]
    if (workspace != null) {
      if (entryPath == null) {
        workspaceDeps += bsn
      } else {
        entries += CompileClasspathEntry.WorkspaceResource(workspace, entryPath)
      }
    } else {
      problems += CompileClasspathProblem(
        token,
        CompileClasspathProblemType.MISSING_BUNDLE,
        "Bundle '$bsn' not found in workspace or target index"
      )
    }
  }
}
