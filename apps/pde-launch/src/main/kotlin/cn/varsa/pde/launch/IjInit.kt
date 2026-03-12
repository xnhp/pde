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

  fun main(args: Array<String>): Int {
    CliLogging.configure(CliLogLevel.INFO, CliStyle.useColor(ColorMode.AUTO))
    val parser = ArgParser("pde ij-init [usable]")
    val issueDirOpt by parser.option(
      ArgType.String,
      fullName = "issue-dir",
      description = "Issue directory containing config.yaml and repos"
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
      val workingDir = if (issueDirOpt != null) {
        issueDir.toAbsolutePath().normalize()
      } else {
        configPath.parent?.toAbsolutePath()?.normalize() ?: issueDir.toAbsolutePath().normalize()
      }
      val moduleCount = initIjProjectFromConfig(configPath, workingDir)
      logger.info("IntelliJ PDE project initialized for ${moduleCount} workspace bundles.")
      0
    } catch (ex: Exception) {
      logger.log(Level.SEVERE, ex.message ?: "Ij-init failed", ex)
      1
    }
  }

  internal fun initIjProjectFromConfig(configPath: Path, workingDir: Path = configPath.parent ?: configPath): Int {
    val baseDir = configPath.parent ?: fail("Config file has no parent directory: ${configPath}")
    val context = LaunchConfigLoader.load(configPath, workingDir)
    if (context.config.bundlesPerRepo.isEmpty()) {
      fail("No bundlesPerRepo entries found in config; add bundlesPerRepo to generate modules.")
    }
    val projectDir = ensureIjProjectDir(baseDir)
    applyIjConfig(context, projectDir)
    return writeIjModules(context, projectDir)
  }

  internal fun copyIjTemplate(targetDir: Path) {
    templateDirs.forEach { dir -> Files.createDirectories(targetDir.resolve(dir)) }
    val loader = IjInit::class.java.classLoader
    templateFiles.forEach { template ->
      val destination = targetDir.resolve(template.destinationPath)
      Files.createDirectories(destination.parent)
      val stream = loader.getResourceAsStream(template.resourcePath)
        ?: fail("Template resource missing: ${template.resourcePath}")
      stream.use { input ->
        Files.copy(input, destination)
      }
    }
    ensureModuleExcludesBin(targetDir.resolve("ij-project.iml"))
  }

  private fun ensureIjProjectDir(baseDir: Path): Path {
    val targetDir = baseDir.resolve("ij-project")
    if (!Files.exists(targetDir)) {
      copyIjTemplate(targetDir)
    } else if (!Files.isDirectory(targetDir)) {
      fail("Expected ${targetDir} to be a directory")
    }
    return targetDir
  }

  private fun applyIjConfig(context: LaunchConfigContext, projectDir: Path) {
    val profilePath = resolveProfilePath(context)
    if (profilePath != null) {
      updateEclipseTargetLocation(projectDir, normalizeProfilePath(profilePath.toString()))
    }
    val formatterConfigPath = context.config.formatterConfigPath?.trim().orEmpty()
    if (formatterConfigPath.isNotEmpty()) {
      val resolved = resolvePath(context.baseDir, formatterConfigPath).toString()
      updateEclipseFormatterConfig(projectDir, resolved)
    }
  }

  private fun writeIjModules(context: LaunchConfigContext, projectDir: Path): Int {
    val baseDir = context.baseDir
    val moduleDir = projectDir.resolve("ij-module-files")
    Files.createDirectories(moduleDir)
    val modules = mutableListOf<String>()
    val vcsMappings = mutableSetOf<String>()
    val nonPdeBundles = context.config.nonPdeBundles

    context.config.bundlesPerRepo.forEach { repoEntry ->
      val repoName = repoEntry.repo.trim()
      if (repoName.isEmpty()) fail("Repository name must not be empty")
      val repoDir = baseDir.resolve(repoName)
      if (!Files.exists(repoDir) || !Files.isDirectory(repoDir)) {
        fail("Repo directory does not exist: ${repoDir}")
      }
      vcsMappings.add(repoName.replace('\\', '/'))
      val entryNonPdeBundles = repoEntry.nonPdeBundles
      val bundleNames = allBundles(repoEntry, repoDir, nonPdeBundles + entryNonPdeBundles)
      if (bundleNames.isEmpty()) fail("No bundles resolved for repo: ${repoName}")
      bundleNames.forEach { bundle ->
        val bundleDir = repoDir.resolve(bundle)
        if (!Files.exists(bundleDir) || !Files.isDirectory(bundleDir)) {
          fail("Bundle directory does not exist: ${bundleDir}")
        }
        val sourceRoots = determineSourceRoots(bundleDir)
        if (sourceRoots.isEmpty()) {
          logger.warning("Bundle has no source roots: ${bundleDir}; skipping")
          return@forEach
        }
        val moduleFileName = "${bundle}.iml"
        val moduleFile = moduleDir.resolve(moduleFileName)
        val contentRoot = normalizeContentRoot(bundleDir.toAbsolutePath().normalize().toUri().toString())
        val relativeSourceRoots = sourceRoots.map { root ->
          bundleDir.relativize(root).toString().replace('\\', '/')
        }
        val excludeFolders = determineExcludedFolders(bundleDir)
        writeModuleFile(moduleFile, contentRoot, relativeSourceRoots, excludeFolders)
        modules.add(moduleFileName)
      }
    }

    writeModulesXml(projectDir, modules)
    writeVcsXml(projectDir, vcsMappings.sorted())
    return modules.size
  }

  private fun allBundles(repoEntry: cn.varsa.pde.resolver.cli.config.RepoBundles, repoDir: Path, nonPdeBundles: List<String>): List<String> {
    val bundles = repoEntry.bundles.map { it.name }
    val nonPde = nonPdeBundles.filter { name -> Files.isDirectory(repoDir.resolve(name)) }
    return (bundles + nonPde).distinct()
  }

  internal fun updateEclipseTargetLocation(projectDir: Path, profilePath: String) {
    val file = projectDir.resolve(".idea/eclipse-partial.xml")
    val contents = Files.readString(file)
    val match = eclipseTargetLocationRegex.find(contents)
      ?: fail("Could not find eclipse location entry in ${file}")
    val escaped = xmlEscape(profilePath)
    val replaced = contents.replaceRange(match.range, "${match.groupValues[1]}${escaped}${match.groupValues[3]}")
    if (replaced == contents) {
      fail("Target location already up to date in ${file}")
    }
    Files.writeString(file, replaced)
  }

  internal fun updateEclipseFormatterConfig(projectDir: Path, formatterConfigPath: String) {
    val file = projectDir.resolve(".idea/eclipseCodeFormatter.xml")
    Files.createDirectories(file.parent)
    val escaped = xmlEscape(formatterConfigPath)
    val xml = """
      <project version=\"4\">
        <component name=\"EclipseCodeFormatterProjectSettings\">
          <option name=\"projectSpecificProfile\">
            <ProjectSpecificProfile>
              <option name=\"formatter\" value=\"ECLIPSE\" />
              <option name=\"pathToConfigFileJava\" value=\"${escaped}\" />
              <option name=\"selectedJavaProfile\" value=\"valid 'org.eclipse.jdt.core.prefs' config\" />
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

  private fun resolveProfilePath(context: LaunchConfigContext): Path? {
    val baseDir = context.baseDir
    val targetConfig = context.config.target
    val legacyProfile = context.config.profilePath?.takeUnless { it.isBlank() }
      ?.let { resolveProfilePath(baseDir, it) }
      ?.let { Paths.get(it) }
    if (targetConfig == null) return legacyProfile

    val profileId = targetConfig.profileId ?: "profile"
    val p2Path = targetConfig.p2Path?.let { resolvePath(baseDir, it) }
      ?: context.workingDir.resolve("target").resolve("p2")
    val registry = p2Path.resolve("org.eclipse.equinox.p2.engine").resolve("profileRegistry")
    val profileDir = registry.resolve("${profileId}.profile")
    val profileFile = profileDir.resolve("Profile.profile")
    val profileFileLegacy = profileDir.resolve("Profile.Profile")
    return when {
      Files.exists(profileFile) -> profileFile
      Files.exists(profileFileLegacy) -> profileFileLegacy
      else -> legacyProfile
    }
  }

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
      "config.yaml",
      "config.yml",
      "launch.yaml",
      "launch.yml",
      "pde.yaml",
      "pde.yml",
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
    val srcDir = bundleDir.resolve("src")
    if (!Files.exists(srcDir) || !Files.isDirectory(srcDir)) return emptyList()
    val roots = mutableListOf<Path>()
    val eclipseDir = srcDir.resolve("eclipse")
    if (Files.isDirectory(eclipseDir)) roots.add(eclipseDir)
    val generatedDir = srcDir.resolve("generated")
    if (Files.isDirectory(generatedDir)) roots.add(generatedDir)
    if (roots.isEmpty()) roots.add(srcDir)
    return roots
  }

  private fun determineExcludedFolders(bundleDir: Path): List<String> {
    val excludes = mutableListOf("bin")
    if (Files.exists(bundleDir.resolve("package.json"))) {
      excludes.add("node_modules")
    }
    return excludes
  }

  private fun normalizeContentRoot(url: String): String {
    if (!url.endsWith("/") || url.length <= "file:///".length) return url
    return url.removeSuffix("/")
  }

  private fun writeModulesXml(projectDir: Path, moduleFiles: List<String>) {
    val builder = StringBuilder()
    builder.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    builder.appendLine("<project version=\"4\">")
    builder.appendLine("  <component name=\"ProjectModuleManager\">")
    builder.appendLine("    <modules>")
    builder.appendLine("      <module fileurl=\"file://\$PROJECT_DIR\$/ij-project.iml\" filepath=\"\$PROJECT_DIR\$/ij-project.iml\" />")
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
      builder.appendLine("    <mapping directory=\"\$PROJECT_DIR\$/../${normalized}\" vcs=\"Git\" />")
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
    builder.appendLine("    <facet type=\"cn.varsa.idea.pde.partial.plugin\" name=\"Eclipse PDE Partial\">")
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
