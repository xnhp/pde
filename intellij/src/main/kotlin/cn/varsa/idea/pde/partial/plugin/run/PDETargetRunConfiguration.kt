package cn.varsa.idea.pde.partial.plugin.run

import cn.varsa.idea.pde.partial.common.domain.DevModule
import cn.varsa.pde.resolver.manifest.BundleManifest
import cn.varsa.idea.pde.partial.common.service.*
import cn.varsa.idea.pde.partial.common.support.*
import cn.varsa.idea.pde.partial.plugin.cache.*
import cn.varsa.idea.pde.partial.plugin.config.*
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.i18n.EclipsePDEPartialBundles.message
import cn.varsa.idea.pde.partial.plugin.helper.PdeNotifier
import cn.varsa.idea.pde.partial.plugin.launch.LauncherPlanBuilder
import cn.varsa.idea.pde.partial.plugin.support.*
import cn.varsa.pde.resolver.launch.*
import com.intellij.diagnostic.logging.*
import com.intellij.execution.*
import com.intellij.execution.application.*
import com.intellij.execution.configurations.*
import com.intellij.execution.filters.*
import com.intellij.execution.runners.*
import com.intellij.execution.util.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.*
import com.intellij.openapi.options.*
import com.intellij.openapi.project.*
import com.intellij.openapi.roots.*
import com.intellij.openapi.util.*
import com.intellij.openapi.vfs.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.execution.*
import cn.varsa.pde.resolver.product.ProductConfigurationParser
import org.jdom.Element
import java.io.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

class PDETargetRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
  ApplicationConfiguration(name, project, factory) {
  private val target by lazy { TargetDefinitionService.getInstance(project) }
  private val cache by lazy { BundleManifestCacheService.getInstance(project) }
  private val compiler by lazy { CompilerProjectExtension.getInstance(project) }
  private val productFiles by lazy { findProductFiles(project) }

  companion object {
    private const val PRODUCT_EXTENSION = "product"
    private const val PDE_JUNIT_PLUGIN_TEST_APPLICATION = "org.eclipse.pde.junit.runtime.coretestapplication"
    private const val JUNIT5_TEST_LOADER = "org.eclipse.jdt.internal.junit5.runner.JUnit5TestLoader"
    private const val JUNIT5_RUNTIME_BUNDLE_NAME = "org.eclipse.jdt.junit5.runtime"
  }

  var product: String? = "com.teamcenter.rac.aifrcp.product"
  var application: String? = "com.teamcenter.rac.aifrcp.application"
  var splashBundlePath: String = "org.knime.product"
  var dataDirectory: String = File(
    compiler?.compilerOutputPointer?.presentableUrl ?: project.presentableUrl, "pde-runtime"
  ).absolutePath
  var cleanRuntimeDir = false
  var additionalClasspath = ""
  var targetModules: Set<String>? = null

  /**
   * Finds all files with the ".product" extension within the project content roots.
   */
  private fun findProductFiles(project: Project): List<Path> {
    val foundFiles = mutableListOf<Path>()
    val projectFileIndex = ProjectFileIndex.getInstance(project)
    ReadAction.run<Throwable> {
      projectFileIndex.iterateContent { virtualFile ->
        if (!virtualFile.isDirectory &&
          projectFileIndex.isInContent(virtualFile) &&
          PRODUCT_EXTENSION.equals(virtualFile.extension, ignoreCase = true)
        ) {
          foundFiles.add(Paths.get(virtualFile.path))
        }
        true
      }
    }
    return foundFiles
  }

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
    SettingsEditorGroup<PDETargetRunConfiguration>().apply {
      addEditor(message("run.local.config.tab.configuration.title"), PDETargetRunConfigurationEditor(this@PDETargetRunConfiguration))
      JavaRunConfigurationExtensionManager.instance.appendEditors(this@PDETargetRunConfiguration, this)
      addEditor(message("run.local.config.tab.logs.title"), LogConfigurationPanel())
    }

  override fun checkConfiguration() {
    JavaParametersUtil.checkAlternativeJRE(this)
    ProgramParametersUtil.checkWorkingDirectoryExist(this, project, configurationModule.module)
    JavaRunConfigurationExtensionManager.checkConfigurationIsValid(this)

    if (compiler == null) throw RuntimeConfigurationWarning(message("run.local.config.noCompiler", project.name))
    if (target.launcherJar == null) throw RuntimeConfigurationWarning(message("run.local.config.noTargetLauncherJar"))
    if (application.isNullOrBlank()) throw RuntimeConfigurationWarning(message("run.local.config.noTargetApplication"))

    if (dataDirectory.isBlank()) throw RuntimeConfigurationWarning(message("run.local.config.noDataDirectory"))

    val dir = File(dataDirectory)
    if (dir.isFile) throw RuntimeConfigurationWarning(message("run.local.config.dataDirectoryNotDirectory"))
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    element.getOrCreateChild("pde").apply {
      setAttribute("product", product ?: "")
      setAttribute("application", application ?: "")
      setAttribute("splashBundlePath", splashBundlePath)
      setAttribute("dataDirectory", dataDirectory)
      setAttribute("cleanRuntimeDir", cleanRuntimeDir.toString())
      setAttribute("additionalClasspath", additionalClasspath)
      setAttribute("targetModules", targetModules?.joinToString(",") ?: "")
    }
  }

  override fun readExternal(element: Element) {
    super.readExternal(element)
    (element.getChild("pde") ?: element.getChild("partial"))?.also {
      product = it.getAttributeValue("product") ?: ""
      application = it.getAttributeValue("application") ?: ""
      splashBundlePath = it.getAttributeValue("splashBundlePath") ?: ""
      dataDirectory = it.getAttributeValue("dataDirectory") ?: File(
        compiler?.compilerOutputPointer?.presentableUrl ?: project.presentableUrl, "pde-runtime"
      ).absolutePath
      cleanRuntimeDir = it.getAttributeValue("cleanRuntimeDir", cleanRuntimeDir.toString()).toBoolean()
      additionalClasspath = it.getAttributeValue("additionalClasspath") ?: ""
      val targetModuleAttribute = it.getAttributeValue("targetModules")
      targetModules =
        if (targetModuleAttribute != null && targetModuleAttribute.isNotBlank()) it.getAttributeValue("targetModules").split(",").toSet()
        else null
      // excluded bundles removed
    }
  }

  override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState =
    PDEApplicationCommandLineState(this, env).apply {
      consoleBuilder =
        TextConsoleBuilderFactory.getInstance().createBuilder(project, GlobalSearchScope.allScope(project))
    }

  private inner class PDEApplicationCommandLineState(
    configuration: PDETargetRunConfiguration, environment: ExecutionEnvironment?
  ) : JavaApplicationCommandLineState<PDETargetRunConfiguration>(
    configuration, environment
  ) {
    private var remoteTestRunnerClient : RemoteTestRunnerClient? = null
    override fun createJavaParameters(): JavaParameters {
      val parameters: JavaParameters = super.createJavaParameters()

      if (cleanRuntimeDir && configServiceDelegate.dataPath.exists()) {
        try {
          configServiceDelegate.dataPath.deleteRecursively()
        } finally {
          configServiceDelegate.dataPath.delete()
        }
      }

      try {
        if (SystemInfo.isMac) parameters.vmParametersList.add("-XstartOnFirstThread")

        parameters.classPath.clear()
        parameters.classPath.add(target.launcherJar!!)
        parameters.classPath.addAll(ParametersListUtil.COLON_LINE_PARSER.apply(additionalClasspath))

        if (target.launcher != null) {
          parameters.programParametersList.addAll("-launcher", target.launcher)
        }

        parameters.programParametersList.addAll("-name", "Eclipse")
        parameters.programParametersList.addAll("-showsplash", "600")

        parameters.programParametersList.addAll("-application", application)

        val properties =
          target.locations.map { File(it.location, "configuration/config.ini") }.firstOrNull(File::exists)
            ?.inputStream()?.use { Properties().apply { load(it) } } ?: Properties()

        val launchOptions = LauncherOptions(
          product = product?.takeIf { it.isNotBlank() },
          application = application?.takeIf { it.isNotBlank() },
          splashBSN = splashBundlePath.takeIf { it.isNotBlank() }
        )
        val planResult = LauncherPlanBuilder.build(project, configServiceDelegate, launchOptions)
        val plan = planResult.plan
        val ctx = planResult.context

        val layout = RuntimeLayoutWriter.LayoutPaths(
          configDir = configServiceDelegate.configurationDirectory.toPath(),
          configIniFile = configServiceDelegate.configIniFile.toPath(),
          devPropertiesFile = configServiceDelegate.devPropertiesFile.toPath(),
          bundlesInfoFile = configServiceDelegate.bundlesInfoFile.toPath()
        )
        val defaults = RuntimeLayoutWriter.Defaults(
          installArea = configServiceDelegate.installArea.toPath(),
          instanceArea = configServiceDelegate.instanceArea.toPath(),
          fallbackConfig = properties
        )
        RuntimeLayoutWriter.write(layout, plan, ctx, launchOptions, defaults)

        parameters.programParametersList.addAll("-data", configServiceDelegate.dataPath.absolutePath)
        parameters.programParametersList.addAll(
          "-configuration", configServiceDelegate.configurationDirectory.protocolUrl
        )
        parameters.programParametersList.addAll("-dev", configServiceDelegate.devPropertiesFile.protocolUrl)

        parameters.programParametersList.add("-consoleLog")

        // add program parameters for junit plugin test
        if (application == PDE_JUNIT_PLUGIN_TEST_APPLICATION) {
          parameters.programParametersList.addAll("-testLoaderClass", JUNIT5_TEST_LOADER)
          parameters.programParametersList.addAll("-loaderpluginname", JUNIT5_RUNTIME_BUNDLE_NAME)
          remoteTestRunnerClient = RemoteTestRunnerClient()
          val port = remoteTestRunnerClient!!.createServerSocket()
          parameters.programParametersList.addAll("-port", port.toString())
        }
      } catch (e: Exception) {
        thisLogger().error(e.message, e)
        throw e
      }

      return parameters
    }

    override fun execute(
      executor: Executor, runner: ProgramRunner<*>
    ): ExecutionResult {
      val executionResult = super.execute(executor, runner)
      if (remoteTestRunnerClient != null)
        remoteTestRunnerClient!!.start(executionResult)
      return executionResult
    }
  }

  private fun findPluginConfigurations(): Map<String, Int> {
    val productId = product ?: return emptyMap()
    productFiles.forEach { path ->
      val parsed = ProductConfigurationParser.parseAutoStartPlugins(path, productId)
      if (parsed != null) return parsed
    }
    return emptyMap()
  }

  private val configServiceDelegate = object : ConfigService {
    override val product: String get() = this@PDETargetRunConfiguration.product ?: ""
    override val application: String get() = this@PDETargetRunConfiguration.application ?: ""
    override val splashBundlePath: String get() = this@PDETargetRunConfiguration.splashBundlePath
    private val configurationMap = mutableMapOf<String, Map<String, Int>>()

    private fun getPluginConfiguration() : Map<String, Int>? {
      if (configurationMap.containsKey(product)) {
        return configurationMap[product]
      } else {
        val configs = this@PDETargetRunConfiguration.findPluginConfigurations()
        configurationMap[product] = configs
        return configs
      }
    }

    override val dataPath: File
      get() = File(dataDirectory)
    override val installArea: File
      get() = (target.launcher?.toFile() ?: target.launcherJar!!.toFile().parentFile).parentFile
    override val projectDirectory: File get() = project.presentableUrl!!.toFile()

    override val libraries: List<File>
      get() {
        val tp = PluginTargetIndexService.getInstance(project)
        return tp.getIndex().bundlesByBsn().values
          .flatMap { it.values }
          .filter { it.manifest.eclipseSourceBundle == null }
          .map { it.location.toFile() }
      }

    override val devModules: List<DevModule>
      get() {
        val devs = project
          .allPDEModules()
          .filter { targetModules == null || it.name in targetModules!! }
          .mapNotNull { PDEFacet.getInstance(it) }
          .map(PDEFacet::toDevModule)
        return devs
      }

    override fun getManifest(jarFileOrDirectory: File): BundleManifest? =
      LocalFileSystem.getInstance().findFileByIoFile(jarFileOrDirectory)?.let { cache.getManifest(it) }

    override fun startUpLevel(bundleSymbolicName: String): Int =
      getPluginConfiguration()?.let{ it[bundleSymbolicName] }?: target.startupLevels[bundleSymbolicName] ?: 4
    override fun isAutoStartUp(bundleSymbolicName: String): Boolean {
      return if (getPluginConfiguration()?.containsKey(bundleSymbolicName) == true) true
      else target.startupLevels.containsKey(bundleSymbolicName)
    }
  }

  /**
   * Check that each dev module's mapped class roots exist and contain classes.
   * Warns via notification if any mapping looks stale or empty.
   */
  fun collectDevMappingProblems(): List<String> {
    val devs = project
      .allPDEModules()
      .filter { targetModules == null || it.name in (targetModules ?: emptySet()) }
      .mapNotNull { PDEFacet.getInstance(it)?.toDevModule() }

    if (devs.isEmpty()) return emptyList()

    val projectDir = project.presentableUrl?.toFile() ?: return emptyList()

    val problems = mutableListOf<String>()
    devs.forEach { dm ->
      val moduleDir = File(projectDir, dm.relativePathToProject)
      dm.compilerClassRelativePathToModule.forEach { rel ->
        val root = File(moduleDir, rel)
        if (!root.exists() || !root.isDirectory) {
          problems += "${dm.bundleSymbolicName}: missing class root ${root.absolutePath}"
        } else {
          val hasClass = root.walkTopDown().take(200).any { it.isFile && it.name.endsWith(".class", ignoreCase = true) }
          if (!hasClass) problems += "${dm.bundleSymbolicName}: no .class files under ${root.absolutePath}"
        }
      }
    }

    return problems
  }

  fun notifyDevMappingProblems(problems: List<String>) {
    if (problems.isEmpty()) {
      PdeNotifier.notification("PDE Preflight", message("run.local.config.preflight.success")).notify(project)
    } else {
      val head = problems.take(5).joinToString("\n • ", prefix = "\n • ")
      val more = if (problems.size > 5) "\n" + message("run.local.config.preflight.more", (problems.size - 5)) else ""
      val text = message("run.local.config.preflight.problems.header") + head + more
      PdeNotifier.important("PDE Preflight", text).notify(project)
    }
  }
}
