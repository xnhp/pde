package cn.varsa.idea.pde.partial.plugin.config

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 *
 */
@Service(Service.Level.PROJECT)
@State(
    name = "EclipsePDEPreferences",
    storages = [Storage("eclipsePdePlugin.xml"), Storage(value = "eclipsePdePartialPlugin.xml", deprecated = true)]
)
class PreferenceService : PersistentStateComponent<PreferenceService> {
    var libraryWhitelist: Set<String> = setOf(
        "org.eclipse.jdt.annotation",
        "org.eclipse.io",
        "org.eclipse.swt"
    )
    var autoResolveTargetOnStartup: Boolean = true

    override fun getState(): PreferenceService = this

    override fun loadState(state: PreferenceService) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): PreferenceService =
            project.getService(PreferenceService::class.java)
    }
}
