package cn.varsa.pde.resolver.compile

import cn.varsa.pde.resolver.launch.BundleStartSpec
import cn.varsa.pde.resolver.launch.LauncherPlan
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import java.nio.file.Path

/**
 * Adjusts launcher plans to point workspace bundles to their compiled output directories.
 */
fun rewritePlanWithCompiledOutputs(plan: LauncherPlan, specs: List<CompileSpec>): LauncherPlan {
  val outputsByBsn = specs.associate { spec ->
    val out = spec.outputDirectory
      ?: Path.of(spec.bundlePath).resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR).toString()
    spec.bsn to Path.of(out).toAbsolutePath().normalize()
  }
  val rewrittenBundles: List<BundleStartSpec> = plan.bundles.map { b ->
    outputsByBsn[b.bsn]?.takeIf { b.isWorkspace }?.let { outPath ->
      b.copy(location = outPath)
    } ?: b
  }
  val rewrittenFramework = rewrittenBundles.firstOrNull { it.bsn == plan.framework?.bsn }
  return plan.copy(bundles = rewrittenBundles, framework = rewrittenFramework)
}
