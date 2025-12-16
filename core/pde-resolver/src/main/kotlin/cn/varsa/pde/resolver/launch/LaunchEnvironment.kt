package cn.varsa.pde.resolver.launch

import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.ResolveResult
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import org.osgi.framework.Version
import java.nio.file.Path

/**
 * Describes all inputs required to assemble a launch plan independent of the IDE.
 */
data class LaunchEnvironment(
  val targetIndex: TargetPlatformIndex,
  val workspaceEntries: List<WorkspaceBundleDescriptor>,
  val resolverOptions: ResolveOptions = ResolveOptions(),
  val libraryBundles: List<SupplementalBundle> = emptyList(),
  val requiredStartupBundles: Set<String> = emptySet(),
  val startupLevels: Map<String, Int> = emptyMap(),
  val startLevelProvider: ((String) -> Int)? = null,
  val autoStartBundles: Map<String, Boolean> = emptyMap(),
  val autoStartProvider: ((String) -> Boolean)? = null,
  val devProperties: Map<String, List<String>> = emptyMap()
) {
  data class SupplementalBundle(
    val bsn: String,
    val version: Version,
    val location: Path,
    val isWorkspace: Boolean = false
  )
}

/**
 * Allows the launcher planner to cache ResolveResult instances across invocations.
 */
interface LaunchResolveSession {
  fun get(entry: WorkspaceBundleDescriptor): ResolveResult?
  fun put(entry: WorkspaceBundleDescriptor, result: ResolveResult)
}

object LaunchResolveSessions {
  val NOOP: LaunchResolveSession = object : LaunchResolveSession {
    override fun get(entry: WorkspaceBundleDescriptor): ResolveResult? = null
    override fun put(entry: WorkspaceBundleDescriptor, result: ResolveResult) {}
  }
}
