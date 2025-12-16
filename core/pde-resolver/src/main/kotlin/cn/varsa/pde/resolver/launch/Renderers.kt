package cn.varsa.pde.resolver.launch

import java.io.File
import java.util.Properties

object ConfigIniRenderer {
  fun toProperties(plan: LauncherPlan, opts: LauncherOptions): Properties {
    val p = Properties()
    plan.properties.forEach { (k, v) -> p[k] = v }
    opts.product?.takeIf { it.isNotBlank() }?.let { p["eclipse.product"] = it }
    opts.application?.takeIf { it.isNotBlank() }?.let { p["eclipse.application"] = it }
    p.putIfAbsent("osgi.bundles.defaultStartLevel", opts.defaultStartLevel.toString())

    plan.framework?.let { fw ->
      p["osgi.framework"] = fw.location.toUriString()
    }
    opts.splashBSN?.let { splashBsn ->
      plan.bundles.firstOrNull { it.bsn == splashBsn }?.let { p["osgi.splashPath"] = it.location.toUriString() }
    }
    if (opts.frameworkExtensions.isNotEmpty()) {
      val uris = opts.frameworkExtensions.mapNotNull { extBsn ->
        plan.bundles.firstOrNull { it.bsn == extBsn }?.location?.toUriString()
      }
      if (uris.isNotEmpty()) p["osgi.framework.extensions"] = uris.joinToString(",")
    }
    return p
  }
}

object BundlesInfoRenderer {
  fun toText(plan: LauncherPlan): String {
    val sb = StringBuilder()
    sb.appendLine("#version=1")
    plan.bundles.sortedBy { it.bsn }.forEach { b ->
      sb.append(b.bsn).append(',')
        .append(b.version.toString()).append(',')
        .append(b.location.toUriString()).append(',')
        .append(b.startLevel.toString()).append(',')
        .appendLine(b.autoStart.toString())
    }
    return sb.toString()
  }
}

object DevPropertiesRenderer {
  fun toProperties(ctx: LaunchContext): Properties {
    val p = Properties()
    ctx.devProperties.forEach { (bsn, paths) ->
      p[bsn] = paths.joinToString(",")
    }
    p["@ignoredot@"] = "true"
    return p
  }
}

private fun java.nio.file.Path.toUriString(): String = this.toFile().toURI().toString()

