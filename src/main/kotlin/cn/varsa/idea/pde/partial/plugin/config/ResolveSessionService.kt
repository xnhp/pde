package cn.varsa.idea.pde.partial.plugin.config

import cn.varsa.pde.resolver.algo.ResolveResult
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.launch.LaunchResolveSession
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches {@link ResolveResult} instances per module and workspace descriptor so IDE workflows
 * (runtime resolver, fragment resolver, launch planning) can share resolver work instead of
 * recomputing it.
 */
@Service(Service.Level.PROJECT)
class ResolveSessionService(private val project: Project) {
  private val moduleResults = ConcurrentHashMap<Module, ResolveResult>()
  private val descriptorResults = ConcurrentHashMap<String, ResolveResult>()

  private fun WorkspaceBundleDescriptor.key(): String =
    path.toAbsolutePath().normalize().toString()

  companion object {
    @JvmStatic
    fun getInstance(project: Project): ResolveSessionService =
      project.getService(ResolveSessionService::class.java)
  }

  fun put(module: Module, descriptor: WorkspaceBundleDescriptor?, result: ResolveResult) {
    moduleResults[module] = result
    descriptor?.let { descriptorResults[it.key()] = result }
  }

  fun put(descriptor: WorkspaceBundleDescriptor, result: ResolveResult) {
    descriptorResults[descriptor.key()] = result
  }

  fun get(module: Module): ResolveResult? = moduleResults[module]

  fun get(descriptor: WorkspaceBundleDescriptor): ResolveResult? = descriptorResults[descriptor.key()]

  fun clear() {
    moduleResults.clear()
    descriptorResults.clear()
  }

  fun asLaunchSession(): LaunchResolveSession = object : LaunchResolveSession {
    override fun get(entry: WorkspaceBundleDescriptor): ResolveResult? = this@ResolveSessionService.get(entry)
    override fun put(entry: WorkspaceBundleDescriptor, result: ResolveResult) = this@ResolveSessionService.put(entry, result)
  }
}
