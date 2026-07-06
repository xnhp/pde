package cn.varsa.idea.pde.partial.plugin.config

import com.intellij.openapi.util.registry.Registry

object PdeFeatureFlags {
  private const val BUNDLE_WHITELIST_KEY = "cn.varsa.idea.pde.tools.bundle.whitelist"

  val bundleWhitelist: Boolean
    get() = Registry.`is`(BUNDLE_WHITELIST_KEY)
}
