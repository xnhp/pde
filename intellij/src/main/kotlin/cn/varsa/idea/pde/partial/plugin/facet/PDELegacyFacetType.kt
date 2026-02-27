package cn.varsa.idea.pde.partial.plugin.facet

import com.intellij.facet.Facet
import com.intellij.facet.FacetType
import com.intellij.facet.FacetTypeId
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import javax.swing.Icon

class PDELegacyFacetType : FacetType<PDEFacet, PDEFacetConfiguration>(id, id.toString(), "PDE Tools") {
  companion object {
    val id = FacetTypeId<PDEFacet>("cn.varsa.idea.pde.partial.plugin")
    fun getInstance(): PDELegacyFacetType = findInstance(PDELegacyFacetType::class.java)
  }

  override fun createFacet(
    module: Module, name: String, configuration: PDEFacetConfiguration, underlyingFacet: Facet<*>?
  ): PDEFacet = PDEFacet(this, module, name, configuration, underlyingFacet)

  override fun createDefaultConfiguration(): PDEFacetConfiguration = PDEFacetConfiguration()
  override fun isSuitableModuleType(moduleType: ModuleType<*>?): Boolean = moduleType is JavaModuleType
  override fun getIcon(): Icon = AllIcons.Providers.Eclipse
}
