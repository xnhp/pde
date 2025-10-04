package cn.varsa.idea.pde.partial.plugin.config

import cn.varsa.pde.resolver.algo.ResolveResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ResolveSessionService(private val project: Project) {
  private val map = ConcurrentHashMap<Module, ResolveResult>()

  companion object {
    @JvmStatic
    fun getInstance(project: Project): ResolveSessionService =
      project.getService(ResolveSessionService::class.java)
  }

  fun put(module: Module, result: ResolveResult) {
    map[module] = result
  }

  fun get(module: Module): ResolveResult? = map[module]

  fun remove(module: Module) {
    map.remove(module)
  }

  fun clear() {
    map.clear()
  }
}

