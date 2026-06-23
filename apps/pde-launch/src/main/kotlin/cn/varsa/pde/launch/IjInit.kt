package cn.varsa.pde.launch

import cn.varsa.cli.core.CliFailure
import cn.varsa.cli.core.CliLogLevel
import cn.varsa.cli.core.CliLogging
import cn.varsa.cli.core.CliStyle
import cn.varsa.cli.core.ColorMode
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.optional
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

object IjInit {
  private val logger = Logger.getLogger(IjInit::class.java.name)

  private data class TemplateFile(val resourcePath: String, val destinationPath: String)

  private val templateDirs = listOf(
    ".idea",
    ".idea/inspectionProfiles",
    "ij-module-files",
    "src"
  )

  private val templateFiles = listOf(
    TemplateFile("ij-project/_gitignore", ".gitignore"),
    TemplateFile("ij-project/.idea/gitignore", ".idea/.gitignore"),
    TemplateFile("ij-project/.idea/modules.xml", ".idea/modules.xml"),
    TemplateFile("ij-project/.idea/workspace.xml", ".idea/workspace.xml"),
    TemplateFile("ij-project/.idea/misc.xml", ".idea/misc.xml"),
    TemplateFile("ij-project/.idea/eclipse-partial.xml", ".idea/eclipse-partial.xml"),
    TemplateFile("ij-project/.idea/vcs.xml", ".idea/vcs.xml"),
    TemplateFile("ij-project/.idea/inspectionProfiles/Project_Default.xml", ".idea/inspectionProfiles/Project_Default.xml"),
    TemplateFile("ij-project/ij-project.iml", "ij-project.iml")
  )

  private val eclipseTargetLocationRegex = Regex("""(<location[^>]*?\slocation=\")([^\"]+)(\")""")
  private val launcherJarRegex = Regex("""(\slauncherJar=\")([^\"]*)(\")""")

  fun main(args: Array<String>): Int {
    CliLogging.configure(CliLogLevel.INFO, CliStyle.useColor(ColorMode.AUTO))
    val parser = ArgParser("pde ij-init [usable]")
    val issueDirOpt by parser.option(
      ArgType.String,
      fullName = "issue-dir",
      description = "Issue directory containing pde.yaml and repos"
    )
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    val configPos by parser.argument(
      ArgType.String,
      description = "YAML launch configuration (positional)"
    ).optional()
    parser.parse(args)

    val issueDir = issueDirOpt?.let { Paths.get(it) } ?: Paths.get("").toAbsolutePath()
    val explicitConfig = resolveConfigPath(issueDir, configOpt, configPos)
    val configPath = explicitConfig ?: findConfigPath(issueDir)
    if (explicitConfig != null && (configPath == null || !Files.exists(configPath))) {
      logger.severe("Config file not found: ${explicitConfig.toAbsolutePath().normalize()}")
      return 1
    }
    if (configPath == null) {
      val projectDir = ensureIjProjectDir(issueDir)
      logger.info("IntelliJ PDE project initialized in ${projectDir}.")
      return 0
    }

    return try {
      val issueRoot = if (issueDirOpt != null) {
        issueDir.toAbsolutePath().normalize()
      } else {
        configPath.parent?.toAbsolutePath()?.normalize() ?: issueDir.toAbsolutePath().normalize()
      }
      val moduleCount = initIjProjectFromConfig(configPath, issueRoot)
      logger.info("IntelliJ PDE project initialized for ${moduleCount} workspace bundles.")
      0
    } catch (ex: Exception) {
      logger.log(Level.SEVERE, ex.message ?: "Ij-init failed", ex)
      1
    }
  }

  internal fun initIjProjectFromConfig(configPath: Path, issueDir: Path = configPath.parent ?: configPath): Int {
    configPath.parent ?: fail("Config file has no parent directory: ${configPath}")
    val issueRoot = issueDir.toAbsolutePath().normalize()
    val context = LaunchConfigLoader.load(configPath, issueRoot)
    if (context.config.bundles.isEmpty()) {
      fail("No bundle entries found in config; add bundles to generate modules.")
    }
    val projectDir = ensureIjProjectDir(issueRoot)
    applyIjConfig(context, projectDir, issueRoot)
    return writeIjModules(context, projectDir)
  }

  internal fun copyIjTemplate(targetDir: Path, projectName: String = projectNameFromDirectory(targetDir)) {
    templateDirs.forEach { dir -> Files.createDirectories(targetDir.resolve(dir)) }
    val loader = IjInit::class.java.classLoader
    templateFiles.forEach { template ->
      val destinationPath = if (template.destinationPath == "ij-project.iml") {
        "${projectName}.iml"
      } else {
        template.destinationPath
      }
      val destination = targetDir.resolve(destinationPath)
      Files.createDirectories(destination.parent)
      val stream = loader.getResourceAsStream(template.resourcePath)
        ?: fail("Template resource missing: ${template.resourcePath}")
      stream.use { input ->
        Files.copy(input, destination)
      }
    }
    ensureModuleExcludesBin(targetDir.resolve("${projectName}.iml"))
  }

  private fun ensureIjProjectDir(issueDir: Path): Path {
    val projectName = determineProjectName(issueDir)
    val targetDir = issueDir.resolve("ij-project")
    if (!Files.exists(targetDir)) {
      copyIjTemplate(targetDir, projectName)
    } else if (!Files.isDirectory(targetDir)) {
      fail("Expected ${targetDir} to be a directory")
    }
    val moduleFile = targetDir.resolve("${projectName}.iml")
    if (!Files.exists(moduleFile)) {
      copyProjectModuleTemplate(targetDir, projectName)
    }
    return targetDir
  }

  private fun applyIjConfig(context: LaunchConfigContext, projectDir: Path, issueDir: Path) {
    val profilePath = resolveProfilePath(context, issueDir)
    updateEclipseTargetLocation(projectDir, toProjectPath(projectDir, profilePath))
    resolveLauncherJar(context)?.let { launcherJar ->
      updateEclipseLauncherJar(projectDir, toProjectPath(projectDir, launcherJar))
    }
    resolveFormatterConfig(issueDir)?.let { formatterConfig ->
      updateEclipseFormatterConfig(projectDir, toProjectPath(projectDir, formatterConfig))
    }
  }

  private fun writeIjModules(context: LaunchConfigContext, projectDir: Path): Int {
    val baseDir = context.baseDir
    val moduleDir = projectDir.resolve("ij-module-files")
    Files.createDirectories(moduleDir)
    val modules = mutableListOf<String>()
    val vcsMappings = mutableSetOf<String>()

    context.config.bundles.forEach { bundleEntry ->
      val bundlePath = resolvePath(baseDir, bundleEntry.path)
      if (!Files.exists(bundlePath) || !Files.isDirectory(bundlePath)) {
        fail("Bundle directory does not exist: ${bundlePath}")
      }
      vcsMappings.add(toProjectPath(projectDir, findVcsRoot(bundlePath) ?: bundlePath.parent ?: bundlePath))
      val bundle = bundlePath.fileName?.toString() ?: fail("Bundle path has no final segment: ${bundlePath}")
      val sourceRoots = determineSourceRoots(bundlePath)
      if (sourceRoots.isEmpty()) {
        logger.warning("Bundle has no source roots: ${bundlePath}; skipping")
        return@forEach
      }
      val moduleFileName = "${bundle}.iml"
      val moduleFile = moduleDir.resolve(moduleFileName)
      val contentRoot = "file://${toProjectPath(projectDir, bundlePath)}"
      val relativeSourceRoots = sourceRoots.map { root ->
        bundlePath.relativize(root).toString().replace('\\', '/')
      }
      val excludeFolders = determineExcludedFolders(bundlePath)
      writeModuleFile(moduleFile, contentRoot, relativeSourceRoots, excludeFolders)
      modules.add(moduleFileName)
    }

    writeModulesXml(projectDir, modules)
    vcsMappings.add("\$PROJECT_DIR\$")
    writeVcsXml(projectDir, vcsMappings.sorted())
    return modules.size
  }

  internal fun updateEclipseTargetLocation(projectDir: Path, profilePath: String) {
    val file = projectDir.resolve(".idea/eclipse-partial.xml")
    val contents = Files.readString(file)
    val match = eclipseTargetLocationRegex.find(contents)
      ?: fail("Could not find eclipse location entry in ${file}")
    val escaped = xmlEscape(profilePath)
    val replaced = contents.replaceRange(match.range, "${match.groupValues[1]}${escaped}${match.groupValues[3]}")
    if (replaced != contents) {
      Files.writeString(file, replaced)
    }
  }

  internal fun updateEclipseLauncherJar(projectDir: Path, launcherJarPath: String) {
    val file = projectDir.resolve(".idea/eclipse-partial.xml")
    val contents = Files.readString(file)
    val escaped = xmlEscape(launcherJarPath)
    if (!launcherJarRegex.containsMatchIn(contents)) {
      fail("Could not find launcherJar entries in ${file}")
    }
    val replaced = launcherJarRegex.replace(contents) { match ->
      "${match.groupValues[1]}${escaped}${match.groupValues[3]}"
    }
    if (replaced != contents) {
      Files.writeString(file, replaced)
    }
  }

  internal fun updateEclipseFormatterConfig(projectDir: Path, formatterConfigPath: String) {
    val file = projectDir.resolve(".idea/eclipseCodeFormatter.xml")
    Files.createDirectories(file.parent)
    val escaped = xmlEscape(formatterConfigPath)
    val xml = """
      <?xml version="1.0" encoding="UTF-8"?>
      <project version="4">
        <component name="EclipseCodeFormatterProjectSettings">
          <option name="projectSpecificProfile">
            <ProjectSpecificProfile>
              <option name="formatter" value="ECLIPSE" />
              <option name="pathToConfigFileJava" value="${escaped}" />
              <option name="selectedJavaProfile" value="valid 'org.eclipse.jdt.core.prefs' config" />
            </ProjectSpecificProfile>
          </option>
        </component>
      </project>
    """.trimIndent()
    Files.writeString(file, xml, StandardCharsets.UTF_8)
  }

  internal fun normalizeProfilePath(value: String): String = value.trim().trimEnd('/', '\\')

  internal fun resolveProfilePath(baseDir: Path, value: String): String {
    val normalized = normalizeProfilePath(value)
    if (normalized.isBlank()) return ""
    val path = Paths.get(normalized)
    return (if (path.isAbsolute) path else baseDir.resolve(path)).normalize().toString()
  }

  private fun resolveProfilePath(context: LaunchConfigContext, issueDir: Path): Path {
    val baseDir = context.baseDir
    val targetConfig = context.config.target
    val profileId = targetConfig?.profileId ?: "profile"
    val p2Path = targetConfig?.p2Path
      ?.takeUnless { isDefaultP2Path(it) }
      ?.let { resolvePath(baseDir, it) }
      ?: issueDir.resolve("target").resolve("p2")
    val registry = p2Path.resolve("org.eclipse.equinox.p2.engine").resolve("profileRegistry")
    return registry.resolve("${profileId}.profile").toAbsolutePath().normalize()
  }

  private fun resolveLauncherJar(context: LaunchConfigContext): Path? {
    val bundlePool = context.config.target?.bundlePool?.takeUnless { it.isBlank() } ?: return null
    val pluginsDir = resolvePath(context.baseDir, bundlePool).resolve("plugins")
    if (!Files.isDirectory(pluginsDir)) return null
    Files.list(pluginsDir).use { stream ->
      return stream
        .filter { path ->
          val name = path.fileName?.toString() ?: return@filter false
          Files.isRegularFile(path) && name.startsWith("org.eclipse.equinox.launcher_") && name.endsWith(".jar")
        }
        .sorted { left, right -> left.fileName.toString().compareTo(right.fileName.toString()) }
        .reduce { _, next -> next }
        .orElse(null)
        ?.toAbsolutePath()
        ?.normalize()
    }
  }

  private fun resolveFormatterConfig(issueDir: Path): Path? {
    val candidates = listOf(
      issueDir.parent?.resolve("org.eclipse.jdt.core.prefs"),
      issueDir.resolve("org.eclipse.jdt.core.prefs")
    ).filterNotNull()
    return candidates.firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.normalize()
  }

  private fun isDefaultP2Path(value: String): Boolean =
    Paths.get(normalizeProfilePath(value)).normalize().toString().replace('\\', '/') == "target/p2"

  private fun resolveConfigPath(baseDir: Path, configOpt: String?, configPos: String?): Path? {
    val candidate = configOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
    return candidate?.let { resolvePath(baseDir, it) }
  }

  private fun resolvePath(baseDir: Path, raw: String): Path {
    val path = Paths.get(raw)
    return if (path.isAbsolute) path else baseDir.resolve(path).normalize()
  }

  private fun looksLikeYamlFile(value: String): Boolean =
    value.endsWith(".yaml", ignoreCase = true) || value.endsWith(".yml", ignoreCase = true)

  internal fun findConfigPath(startDir: Path): Path? {
    val candidates = listOf(
      "pde.yaml",
      "launch.yaml",
      "launch.yml",
      "pde-launch.yaml",
      "pde-launch.yml"
    )
    var current = startDir.toAbsolutePath().normalize()
    while (true) {
      candidates.forEach { name ->
        val path = current.resolve(name)
        if (Files.exists(path) && Files.isRegularFile(path)) return path
      }
      val parent = current.parent ?: return null
      if (parent == current) return null
      current = parent
    }
  }

  private fun determineSourceRoots(bundleDir: Path): List<Path> {
    val props = loadBuildProperties(bundleDir)
    val roots = props?.stringPropertyNames()
      ?.filter { it.startsWith("source.") }
      ?.sorted()
      ?.flatMap { key -> props.getProperty(key).split(',').map { it.trim() } }
      ?.filter { it.isNotEmpty() }
      ?.map { bundleDir.resolve(it).normalize() }
      ?.filter { Files.isDirectory(it) }
      ?.distinct()
      ?: emptyList()
    if (roots.isNotEmpty()) return roots

    return listOf("src/eclipse", "src/generated", "src-deprecated", "js-src", "src")
      .map { bundleDir.resolve(it).normalize() }
      .filter { Files.isDirectory(it) }
  }

  private fun loadBuildProperties(bundleDir: Path): Properties? {
    val file = bundleDir.resolve("build.properties")
    if (!Files.isRegularFile(file)) return null
    return Properties().apply {
      Files.newInputStream(file).use { load(it) }
    }
  }

  private fun determineExcludedFolders(bundleDir: Path): List<String> {
    val excludes = mutableListOf("bin")
    if (Files.exists(bundleDir.resolve("package.json"))) {
      excludes.add("node_modules")
    }
    return excludes
  }

  private fun writeModulesXml(projectDir: Path, moduleFiles: List<String>) {
    val projectName = determineProjectName(projectDir.parent ?: projectDir)
    val builder = StringBuilder()
    builder.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    builder.appendLine("<project version=\"4\">")
    builder.appendLine("  <component name=\"ProjectModuleManager\">")
    builder.appendLine("    <modules>")
    builder.appendLine("      <module fileurl=\"file://\$PROJECT_DIR\$/${projectName}.iml\" filepath=\"\$PROJECT_DIR\$/${projectName}.iml\" />")
    moduleFiles.forEach { file ->
      builder.appendLine("      <module fileurl=\"file://\$PROJECT_DIR\$/ij-module-files/${file}\" filepath=\"\$PROJECT_DIR\$/ij-module-files/${file}\" />")
    }
    builder.appendLine("    </modules>")
    builder.appendLine("  </component>")
    builder.appendLine("</project>")

    val ideaDir = projectDir.resolve(".idea")
    Files.createDirectories(ideaDir)
    Files.writeString(ideaDir.resolve("modules.xml"), builder.toString())
  }

  private fun writeVcsXml(projectDir: Path, repos: List<String>) {
    val builder = StringBuilder()
    builder.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    builder.appendLine("<project version=\"4\">")
    builder.appendLine("  <component name=\"VcsDirectoryMappings\">")
    repos.forEach { repo ->
      val normalized = repo.replace('\\', '/').trimEnd('/')
      builder.appendLine("    <mapping directory=\"${xmlEscape(normalized)}\" vcs=\"Git\" />")
    }
    builder.appendLine("  </component>")
    builder.appendLine("</project>")
    Files.writeString(projectDir.resolve(".idea/vcs.xml"), builder.toString())
  }

  private fun writeModuleFile(
    moduleFile: Path,
    contentRoot: String,
    sourceRoots: List<String>,
    excludeFolders: List<String>
  ) {
    val builder = StringBuilder()
    builder.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    builder.appendLine("<module type=\"JAVA_MODULE\" version=\"4\">")
    builder.appendLine("  <component name=\"FacetManager\">")
    builder.appendLine("    <facet type=\"cn.varsa.idea.pde.tools.plugin\" name=\"PDE Tools\">")
    builder.appendLine("      <configuration />")
    builder.appendLine("    </facet>")
    builder.appendLine("  </component>")
    builder.appendLine("  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"true\">")
    builder.appendLine("    <exclude-output />")
    builder.appendLine("    <content url=\"${contentRoot}\">")
    sourceRoots.forEach { sourceRoot ->
      builder.appendLine("      <sourceFolder url=\"${contentRoot}/${sourceRoot}\" isTestSource=\"false\" />")
    }
    excludeFolders.forEach { exclude ->
      builder.appendLine("      <excludeFolder url=\"${contentRoot}/${exclude}\" />")
    }
    builder.appendLine("    </content>")
    builder.appendLine("    <orderEntry type=\"inheritedJdk\" />")
    builder.appendLine("    <orderEntry type=\"sourceFolder\" forTests=\"false\" />")
    builder.appendLine("  </component>")
    builder.appendLine("</module>")
    Files.writeString(moduleFile, builder.toString())
  }

  private fun ensureModuleExcludesBin(moduleFile: Path) {
    if (!Files.exists(moduleFile)) fail("Module file missing: ${moduleFile}")
    val content = Files.readString(moduleFile)
    val excludeLine = "      <excludeFolder url=\"file://\$MODULE_DIR\$/bin\" />"
    if (content.contains(excludeLine)) return
    val marker = "    </content>"
    val index = content.indexOf(marker)
    if (index == -1) fail("Could not update ${moduleFile} to exclude bin")
    val updated = content.replace(marker, "${excludeLine}\n${marker}")
    Files.writeString(moduleFile, updated)
  }

  private fun copyProjectModuleTemplate(targetDir: Path, projectName: String) {
    val loader = IjInit::class.java.classLoader
    val destination = targetDir.resolve("${projectName}.iml")
    val stream = loader.getResourceAsStream("ij-project/ij-project.iml")
      ?: fail("Template resource missing: ij-project/ij-project.iml")
    stream.use { input -> Files.copy(input, destination) }
    ensureModuleExcludesBin(destination)
  }

  private fun determineProjectName(issueDir: Path): String {
    val issueYaml = issueDir.resolve("issue.yaml")
    if (Files.isRegularFile(issueYaml)) {
      val match = Regex("""(?m)^\s*id:\s*([A-Za-z][A-Za-z0-9]*-\d+)\s*$""")
        .find(Files.readString(issueYaml))
      if (match != null) return match.groupValues[1]
    }
    val dirName = issueDir.fileName?.toString().orEmpty()
    Regex("[A-Z]+-\\d+").find(dirName)?.let { return it.value }
    return sanitizeProjectName(dirName).ifBlank { "ij-project" }
  }

  private fun projectNameFromDirectory(projectDir: Path): String =
    sanitizeProjectName(projectDir.fileName?.toString().orEmpty()).ifBlank { "ij-project" }

  private fun sanitizeProjectName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9_.-]+"), "-").trim('-', '.', '_')

  private fun findVcsRoot(path: Path): Path? {
    var current = path.toAbsolutePath().normalize()
    while (true) {
      if (Files.exists(current.resolve(".git"))) return current
      current = current.parent ?: return null
    }
  }

  private fun toProjectPath(projectDir: Path, path: Path): String {
    val normalizedProjectDir = projectDir.toAbsolutePath().normalize()
    val normalizedPath = path.toAbsolutePath().normalize()
    val relative = normalizedProjectDir.relativize(normalizedPath).toString().replace('\\', '/')
    return if (relative.isBlank()) "\$PROJECT_DIR\$" else "\$PROJECT_DIR\$/${relative}"
  }

  private fun xmlEscape(value: String): String {
    return value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
  }

  private fun fail(message: String): Nothing = throw CliFailure(message)
}
