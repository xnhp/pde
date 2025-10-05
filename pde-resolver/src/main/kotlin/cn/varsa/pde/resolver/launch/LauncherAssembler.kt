package cn.varsa.pde.resolver.launch

import cn.varsa.pde.resolver.algo.ResolveResult

object LauncherAssembler {
  fun from(result: ResolveResult, ctx: LaunchContext, opts: LauncherOptions): LauncherPlan {
    val bundles = result.bundles.map { rb ->
      val level = ctx.startupLevels[rb.bsn] ?: opts.defaultStartLevel
      val auto = opts.autoStartDefault && level >= 0
      BundleStartSpec(
        bsn = rb.bsn,
        version = rb.version,
        location = rb.path,
        startLevel = level,
        autoStart = auto,
        isWorkspace = rb.isWorkspace
      )
    }
    val framework = bundles.firstOrNull { it.bsn == opts.frameworkBSN }
    val props = buildMap<String, String> {
      opts.product?.takeIf { it.isNotBlank() }?.let { put("eclipse.product", it) }
      opts.application.takeIf { !it.isNullOrBlank() }?.let { put("eclipse.application", it) }
      putIfAbsent("osgi.bundles.defaultStartLevel", opts.defaultStartLevel.toString())
    }
    return LauncherPlan(bundles = bundles, framework = framework, properties = props)
  }
}

