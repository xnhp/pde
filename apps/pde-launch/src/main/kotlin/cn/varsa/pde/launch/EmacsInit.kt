package cn.varsa.pde.launch

import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.WorkspaceModuleResolver
import cn.varsa.pde.resolver.compile.CompileService
import cn.varsa.pde.resolver.launch.LaunchEnvironment
import cn.varsa.pde.resolver.launch.LaunchPlanner
import cn.varsa.pde.resolver.launch.LauncherOptions
import cn.varsa.pde.resolver.index.TargetPlatformCache
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.optional
import cn.varsa.pde.remoterunner.ConsoleTags
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object EmacsInit {
  private val logger = Logger.getLogger(EmacsInit::class.java.name)
  private const val defaultWorkspaceDir = ".pde-jdtls"
  private const val defaultExecutionEnvironment = "JavaSE-21"

  private enum class WorkspaceMode {
    IN_PLACE,
    SYMLINK
  }

  fun main(args: Array<String>): Int {
    configureLogging(Level.INFO, shouldUseColor())
    val parser = ArgParser("pde emacs-init")
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    val workspaceModeRaw by parser.option(
      ArgType.String,
      fullName = "workspace-mode",
      description = "Workspace mode: symlink or in-place"
    ).default("symlink")
    val configPos by parser.argument(
      ArgType.String,
      description = "YAML launch configuration (positional)"
    ).optional()
    parser.parse(args)

    logger.level = Level.INFO

    val workingDir = Paths.get("").toAbsolutePath()
    val configPath = resolveConfigPath(workingDir, configOpt, configPos)
    if (configPath == null) {
      logger.severe("No launch config found (config.yaml/launch.yaml/pde.yaml). Use --config.")
      return 1
    }
    val baseDir = configPath.parent ?: workingDir
    val context = LaunchConfigLoader.load(configPath, baseDir)
    val profilePath = resolveProfilePath(context)
    if (profilePath == null || !Files.exists(profilePath)) {
      logger.severe("Target profile registry missing; run pde target-install first.")
      return 1
    }

    val targetIndex = TargetPlatformCache.buildWithCache(listOf(profilePath))
    val workspaceInputs = WorkspaceModuleResolver.resolve(context, allowMissingClasses = true)
    val workspaceEntries = workspaceInputs.descriptors
    if (workspaceEntries.isEmpty()) {
      logger.severe("No workspace modules found in config.")
      return 1
    }

    val env = LaunchEnvironment(
      targetIndex = targetIndex,
      workspaceEntries = workspaceEntries,
      resolverOptions = ResolveOptions(
        whitelistPrefixes = emptySet(),
        preferWorkspace = true,
        includeHostsForFragments = true
      ),
      autoStartBundles = emptyMap(),
      startupLevels = emptyMap(),
      devProperties = emptyMap()
    )
    val options = LauncherOptions(frameworkBSN = "org.eclipse.osgi", autoStartDefault = false)
    val planResult = LaunchPlanner.build(env, options)
    val specs = CompileService.buildSpecs(planResult, workspaceEntries).specs

    val workspaceRoot = baseDir.toAbsolutePath().normalize()
    val workspaceMode = parseWorkspaceMode(workspaceModeRaw)
    if (workspaceMode == null) {
      logger.severe("Unknown workspace mode: $workspaceModeRaw (use symlink or in-place)")
      return 1
    }
    val emacsWorkspaceRoot = if (workspaceMode == WorkspaceMode.SYMLINK) {
      resolvePath(baseDir, defaultWorkspaceDir)
    } else {
      workspaceRoot
    }
    val sourceZips = emptyMap<String, Path>()

    val bundlePool = resolveBundlePool(context)
    val pluginPool = bundlePool?.resolve("plugins")

    val specsByPath = specs.filter { it.isWorkspace }
      .associateBy { Paths.get(it.bundlePath).toAbsolutePath().normalize() }
    val workspaceProjects = workspaceEntries.map { it.path.fileName.toString() }.sorted()

    if (workspaceMode == WorkspaceMode.SYMLINK) {
      Files.createDirectories(emacsWorkspaceRoot)
      setupSymlinkWorkspace(
        workspaceRoot = workspaceRoot,
        workspaceDir = emacsWorkspaceRoot,
        workspaceEntries = workspaceEntries,
        workspaceProjects = workspaceProjects,
        specsByPath = specsByPath,
        pluginPool = pluginPool,
        localSourceZips = sourceZips
      )
    } else {
      workspaceEntries.forEach { descriptor ->
        val moduleDir = descriptor.path.toAbsolutePath().normalize()
        val classpathFile = moduleDir.resolve(".classpath")
        if (!Files.exists(classpathFile)) {
          logger.warning("Skipping ${moduleDir.fileName}: missing .classpath")
          return@forEach
        }
        val spec = specsByPath[moduleDir]
        if (spec == null) {
          logger.warning("Skipping ${moduleDir.fileName}: missing compile spec")
          return@forEach
        }
        updateClasspath(
          classpathFile = classpathFile,
          moduleDir = moduleDir,
          workspaceRoot = workspaceRoot,
          workspaceProjects = workspaceProjects,
          specClasspath = spec.classpath,
          pluginPool = pluginPool,
          localSourceZips = sourceZips
        )
        ensureOutputDirectory(classpathFile, moduleDir)
      }
      ensureDirLocals(workspaceRoot, configPath)
    }

    if (workspaceMode == WorkspaceMode.SYMLINK) {
      logger.info("Emacs/JDT LS setup complete for ${workspaceEntries.size} workspace bundles in ${emacsWorkspaceRoot}.")
    } else {
      logger.info("Emacs/JDT LS setup complete for ${workspaceEntries.size} workspace bundles.")
    }
    return 0
  }

  private fun configureLogging(level: Level, useColor: Boolean) {
    logger.level = level
    val root = Logger.getLogger("")
    root.level = level
    val formatter = createFormatter(useColor)
    root.handlers?.forEach { handler ->
      handler.level = level
      handler.formatter = formatter
    }
  }

  private fun createFormatter(useColor: Boolean) = object : Formatter() {
    override fun format(record: LogRecord): String {
      val message = formatMessage(record)
      val builder = StringBuilder()
      builder.append(logPrefix(record.level, useColor)).append(' ').append(message).append('\n')
      record.thrown?.let { thrown ->
        val writer = StringWriter()
        val printer = PrintWriter(writer)
        thrown.printStackTrace(printer)
        printer.flush()
        builder.append(writer.toString())
      }
      return builder.toString()
    }
  }

  private fun logPrefix(level: Level, useColor: Boolean): String {
    val value = level.intValue()
    return when {
      value >= Level.SEVERE.intValue() -> ConsoleTags.error(useColor)
      value >= Level.WARNING.intValue() -> ConsoleTags.warn(useColor)
      value >= Level.INFO.intValue() -> ConsoleTags.info(useColor)
      value >= Level.FINE.intValue() -> ConsoleTags.debug(useColor)
      else -> ConsoleTags.trace(useColor)
    }
  }

  private fun shouldUseColor(): Boolean = System.console() != null

  private fun resolveConfigPath(baseDir: Path, configOpt: String?, configPos: String?): Path? {
    val candidate = configOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
    if (candidate != null) {
      return resolvePath(baseDir, candidate)
    }
    return discoverConfigFile(baseDir)
  }

  private fun resolvePath(baseDir: Path, raw: String): Path {
    val path = Paths.get(raw)
    return if (path.isAbsolute) path else baseDir.resolve(path).normalize()
  }

  private fun looksLikeYamlFile(value: String): Boolean =
    value.endsWith(".yaml", ignoreCase = true) || value.endsWith(".yml", ignoreCase = true)

  private fun discoverConfigFile(baseDir: Path): Path? {
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
    return candidates
      .map { baseDir.resolve(it) }
      .firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
  }

  private fun resolveProfilePath(context: LaunchConfigContext): Path? {
    val baseDir = context.baseDir
    val targetConfig = context.config.target
    val legacyProfile = context.config.profilePath?.takeUnless { it.isBlank() }
      ?.let { baseDir.resolve(it).normalize() }
    if (targetConfig == null) return legacyProfile
    val profileId = targetConfig.profileId?.takeUnless { it.isBlank() } ?: "profile"
    val p2Path = targetConfig.p2Path?.takeUnless { it.isBlank() } ?: "./target/p2"
    val registryDir = baseDir.resolve(p2Path)
      .resolve("org.eclipse.equinox.p2.engine/profileRegistry")
      .normalize()
    val preferred = registryDir.resolve("$profileId.Profile").normalize()
    if (Files.exists(preferred)) return preferred
    val lowercase = registryDir.resolve("$profileId.profile").normalize()
    return if (Files.exists(lowercase)) lowercase else preferred
  }

  private fun resolveBundlePool(context: LaunchConfigContext): Path? {
    val baseDir = context.baseDir
    val targetConfig = context.config.target ?: return null
    val raw = targetConfig.bundlePool?.takeUnless { it.isBlank() } ?: return null
    return resolvePath(baseDir, raw)
  }

  private fun parseWorkspaceMode(raw: String): WorkspaceMode? {
    return when (raw.lowercase()) {
      "symlink", "symlinks" -> WorkspaceMode.SYMLINK
      "in-place", "inplace" -> WorkspaceMode.IN_PLACE
      else -> null
    }
  }

  private fun setupSymlinkWorkspace(
    workspaceRoot: Path,
    workspaceDir: Path,
    workspaceEntries: List<WorkspaceBundleDescriptor>,
    workspaceProjects: List<String>,
    specsByPath: Map<Path, cn.varsa.pde.resolver.compile.CompileSpec>,
    pluginPool: Path?,
    localSourceZips: Map<String, Path>
  ) {
    workspaceEntries.forEach { descriptor ->
      val moduleDir = descriptor.path.toAbsolutePath().normalize()
      val projectName = moduleDir.fileName.toString()
      val projectDir = workspaceDir.resolve(projectName)
      val spec = specsByPath[moduleDir]
      if (spec == null) {
        logger.warning("Skipping ${projectName}: missing compile spec")
        return@forEach
      }

      if (Files.exists(projectDir)) {
        deleteRecursively(projectDir)
      }
      Files.createDirectories(projectDir)

      val sourceLinks = createSourceSymlinks(projectDir, moduleDir, descriptor)
      writeProjectFile(projectDir, projectName)
      writeClasspathFile(
        projectDir = projectDir,
        workspaceRoot = workspaceRoot,
        workspaceProjects = workspaceProjects,
        sourceLinks = sourceLinks,
        specClasspath = spec.classpath,
        pluginPool = pluginPool,
        localSourceZips = localSourceZips
      )
      Files.createDirectories(projectDir.resolve("bin"))
    }
  }

  private fun createSourceSymlinks(
    projectDir: Path,
    moduleDir: Path,
    descriptor: WorkspaceBundleDescriptor
  ): List<String> {
    val sourceRoots = descriptor.sourceRoots
      .map { it.toAbsolutePath().normalize() }
      .ifEmpty { findSourceRoots(moduleDir) }

    if (sourceRoots.isEmpty()) return emptyList()

    val created = mutableListOf<String>()
    val usedPaths = mutableSetOf<String>()

    sourceRoots.forEachIndexed { index, srcRoot ->
      val linkPath = if (srcRoot.startsWith(moduleDir)) {
        val rel = moduleDir.relativize(srcRoot)
        projectDir.resolve(rel)
      } else {
        projectDir.resolve("src-external-${index + 1}")
      }

      val finalLink = resolveUniquePath(linkPath, usedPaths)
      Files.createDirectories(finalLink.parent)
      Files.createSymbolicLink(finalLink, srcRoot)
      created.add(projectDir.relativize(finalLink).toString())
    }

    return created
  }

  private fun resolveUniquePath(path: Path, used: MutableSet<String>): Path {
    var candidate = path
    var suffix = 1
    while (used.contains(candidate.toString())) {
      candidate = Paths.get(path.toString() + "-$suffix")
      suffix += 1
    }
    used.add(candidate.toString())
    return candidate
  }

  private fun writeProjectFile(projectDir: Path, projectName: String) {
    val factory = DocumentBuilderFactory.newInstance()
    val doc = factory.newDocumentBuilder().newDocument()
    val project = doc.createElement("projectDescription")
    doc.appendChild(project)

    val name = doc.createElement("name")
    name.textContent = projectName
    project.appendChild(name)

    val buildSpec = doc.createElement("buildSpec")
    val buildCommand = doc.createElement("buildCommand")
    val buildName = doc.createElement("name")
    buildName.textContent = "org.eclipse.jdt.core.javabuilder"
    buildCommand.appendChild(buildName)
    buildCommand.appendChild(doc.createElement("arguments"))
    buildSpec.appendChild(buildCommand)
    project.appendChild(buildSpec)

    val natures = doc.createElement("natures")
    val nature = doc.createElement("nature")
    nature.textContent = "org.eclipse.jdt.core.javanature"
    natures.appendChild(nature)
    project.appendChild(natures)

    writeXml(doc, projectDir.resolve(".project"))
  }

  private fun writeClasspathFile(
    projectDir: Path,
    workspaceRoot: Path,
    workspaceProjects: List<String>,
    sourceLinks: List<String>,
    specClasspath: List<String>,
    pluginPool: Path?,
    localSourceZips: Map<String, Path>
  ) {
    val factory = DocumentBuilderFactory.newInstance()
    val doc = factory.newDocumentBuilder().newDocument()
    val root = doc.createElement("classpath")
    doc.appendChild(root)

    sourceLinks.forEach { path ->
      root.appendChild(doc.createElement("classpathentry").apply {
        setAttribute("kind", "src")
        setAttribute("path", path)
      })
    }

    val projectName = projectDir.fileName.toString()
    workspaceProjects
      .filter { it != projectName }
      .forEach { project ->
        root.appendChild(doc.createElement("classpathentry").apply {
          setAttribute("kind", "src")
          setAttribute("path", "/$project")
          setAttribute("combineaccessrules", "false")
        })
      }

    root.appendChild(doc.createElement("classpathentry").apply {
      setAttribute("kind", "con")
      setAttribute("path", "org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/$defaultExecutionEnvironment")
    })

    val libEntries = specClasspath.mapNotNull { raw ->
      val absPath = Paths.get(raw).toAbsolutePath().normalize()
      if (!Files.exists(absPath)) return@mapNotNull null
      if (absPath.startsWith(workspaceRoot)) return@mapNotNull null
      doc.createElement("classpathentry").apply {
        setAttribute("kind", "lib")
        setAttribute("path", absPath.toString())
        setAttribute("exported", "true")
      }
    }
    libEntries.forEach { root.appendChild(it) }

    root.appendChild(doc.createElement("classpathentry").apply {
      setAttribute("kind", "output")
      setAttribute("path", "bin")
    })

    attachSourcesToLibEntries(root, projectDir, pluginPool, localSourceZips)
    writeXml(doc, projectDir.resolve(".classpath"))
  }

  private fun updateClasspath(
    classpathFile: Path,
    moduleDir: Path,
    workspaceRoot: Path,
    workspaceProjects: List<String>,
    specClasspath: List<String>,
    pluginPool: Path?,
    localSourceZips: Map<String, Path>
  ) {
    val doc = parseXml(classpathFile)
    val root = doc.documentElement
    val entries = classpathEntries(root)

    val outputEntry = entries.firstOrNull { it.getAttribute("kind") == "output" }
    val existingLibAbs = entries
      .filter { it.getAttribute("kind") == "lib" }
      .mapNotNull { entry ->
        entry.getAttribute("path")?.takeIf { it.isNotBlank() }?.let { toAbsPath(moduleDir, it) }
      }
      .toMutableSet()

    // Remove PDE container + bad libs
    entries.forEach { entry ->
      val kind = entry.getAttribute("kind")
      val path = entry.getAttribute("path")
      if (kind == "con" && path == "org.eclipse.pde.core.requiredPlugins") {
        root.removeChild(entry)
        return@forEach
      }
      if (kind != "lib" || path.isNullOrBlank()) return@forEach
      val absPath = toAbsPath(moduleDir, path)
      if (!Files.exists(absPath)) {
        root.removeChild(entry)
        return@forEach
      }
      if (absPath.startsWith(workspaceRoot)) {
        val libRoot = moduleDir.resolve("lib")
        val libsRoot = moduleDir.resolve("libs")
        if (absPath.startsWith(libRoot) || absPath.startsWith(libsRoot)) {
          return@forEach
        }
        root.removeChild(entry)
      }
    }

    // Add workspace project refs
    val existingSrc = classpathEntries(root)
      .filter { it.getAttribute("kind") == "src" }
      .mapNotNull { it.getAttribute("path") }
      .toSet()
    val projectName = moduleDir.fileName.toString()
    val newProjectEntries = workspaceProjects
      .filter { it != projectName }
      .map { "/$it" }
      .filter { !existingSrc.contains(it) }
      .map { path ->
        doc.createElement("classpathentry").apply {
          setAttribute("kind", "src")
          setAttribute("path", path)
          setAttribute("combineaccessrules", "false")
        }
      }
    insertBeforeOutput(root, outputEntry, newProjectEntries)

    // Add lib entries from compile spec
    val libEntriesToAdd = specClasspath.mapNotNull { raw ->
      val absPath = Paths.get(raw).toAbsolutePath().normalize()
      if (!Files.exists(absPath)) return@mapNotNull null
      if (absPath.startsWith(workspaceRoot)) return@mapNotNull null
      if (existingLibAbs.contains(absPath)) return@mapNotNull null
      existingLibAbs.add(absPath)
      doc.createElement("classpathentry").apply {
        setAttribute("kind", "lib")
        setAttribute("path", absPath.toString())
        setAttribute("exported", "true")
      }
    }
    insertBeforeOutput(root, outputEntry, libEntriesToAdd)

    attachSourcesToLibEntries(root, moduleDir, pluginPool, localSourceZips)

    writeXml(doc, classpathFile)
  }

  private fun ensureOutputDirectory(classpathFile: Path, moduleDir: Path) {
    val doc = parseXml(classpathFile)
    val outputEntry = classpathEntries(doc.documentElement)
      .firstOrNull { it.getAttribute("kind") == "output" }
    val outputPath = outputEntry?.getAttribute("path")?.takeIf { it.isNotBlank() } ?: "bin"
    Files.createDirectories(moduleDir.resolve(outputPath))
  }

  private fun ensureDirLocals(workspaceRoot: Path, configPath: Path) {
    val dirLocals = workspaceRoot.resolve(".dir-locals.el")
    val markers = linkedSetOf(
      configPath.fileName.toString(),
      "config.yaml",
      "config.yml",
      "launch.yaml",
      "launch.yml",
      "pde.yaml",
      "pde.yml",
      "pde-launch.yaml",
      "pde-launch.yml"
    ).filter { it.isNotBlank() }
    val markersLiteral = markers.joinToString(" ") { "\"$it\"" }
    val rootLiteral = workspaceRoot.toAbsolutePath().normalize().toString()
    val legacyContent = """
      ((nil . ((project-vc-extra-root-markers . ($markersLiteral)))))
    """.trimIndent()
    val content = """
      ;; Generated by pde emacs-init. Safe to delete and re-run.
      ((nil . ((eval . (progn
        (require 'project)
        (require 'cl-lib)
        (defconst pde--emacs-init-root (file-name-as-directory "$rootLiteral"))
        (defun pde--emacs-init-project (dir)
          (let ((tru-root (file-truename pde--emacs-init-root))
                (tru-dir (file-truename dir)))
            (when (string-prefix-p tru-root tru-dir)
              (cons 'pde-emacs-init tru-root))))
        (cl-defmethod project-root ((project (head pde-emacs-init)))
          (cdr project))
        (add-to-list 'project-find-functions #'pde--emacs-init-project)
        (setq project-vc-extra-root-markers '($markersLiteral))))))))
    """.trimIndent() + "\n"
    if (Files.exists(dirLocals)) {
      val existing = Files.readString(dirLocals).trim()
      if (existing == content.trim()) {
        logger.info("Skipping ${dirLocals.fileName}: up to date.")
        return
      }
      if (existing == legacyContent.trim() || existing.contains("pde--emacs-init-root")) {
        Files.writeString(dirLocals, content)
        logger.info("Updated ${dirLocals.fileName} to force Emacs project root at ${workspaceRoot}.")
        return
      }
      logger.info("Skipping ${dirLocals.fileName}: already exists.")
      return
    }
    Files.writeString(dirLocals, content)
    logger.info("Wrote ${dirLocals.fileName} to force Emacs project root at ${workspaceRoot}.")
  }

  private fun buildSourceZips(sourcesRoots: List<String>, outputDir: Path): Map<String, Path> {
    if (sourcesRoots.isEmpty()) return emptyMap()
    Files.createDirectories(outputDir)
    val result = linkedMapOf<String, Path>()
    sourcesRoots.forEach { rootRaw ->
      val root = Paths.get(rootRaw).toAbsolutePath().normalize()
      if (!Files.isDirectory(root)) {
        logger.warning("Sources root does not exist: $root")
        return@forEach
      }
      Files.list(root).use { stream ->
        stream.filter { Files.isDirectory(it) }.forEach { projectRoot ->
          val bundleId = projectRoot.fileName.toString()
          val srcRoots = findSourceRoots(projectRoot)
          if (srcRoots.isEmpty()) return@forEach
          val outZip = outputDir.resolve("$bundleId.src.zip")
          ZipOutputStream(BufferedOutputStream(Files.newOutputStream(outZip))).use { zip ->
            srcRoots.forEach { srcRoot ->
              Files.walk(srcRoot).use { walker ->
                walker.filter { Files.isRegularFile(it) }.forEach { file ->
                  val name = file.fileName.toString()
                  if (!name.endsWith(".java") && !name.endsWith(".kt") && !name.endsWith(".properties") && !name.endsWith(".xml")) {
                    return@forEach
                  }
                  val rel = srcRoot.relativize(file).toString()
                  zip.putNextEntry(ZipEntry(rel))
                  BufferedInputStream(Files.newInputStream(file)).use { input ->
                    input.copyTo(zip)
                  }
                  zip.closeEntry()
                }
              }
            }
          }
          result[bundleId] = outZip
        }
      }
    }
    return result
  }

  private fun findSourceRoots(projectRoot: Path): List<Path> {
    val classpathFile = projectRoot.resolve(".classpath")
    if (Files.exists(classpathFile)) {
      val doc = parseXml(classpathFile)
      val srcEntries = classpathEntries(doc.documentElement)
        .filter { it.getAttribute("kind") == "src" }
        .mapNotNull { it.getAttribute("path") }
        .filterNot { it.startsWith("/") }
        .map { projectRoot.resolve(it).normalize() }
        .filter { Files.isDirectory(it) }
      if (srcEntries.isNotEmpty()) return srcEntries
    }
    val fallback = projectRoot.resolve("src")
    return if (Files.isDirectory(fallback)) listOf(fallback) else emptyList()
  }

  private fun parseXml(path: Path): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    return factory.newDocumentBuilder().parse(path.toFile())
  }

  private fun classpathEntries(root: Element): List<Element> {
    val nodes = root.getElementsByTagName("classpathentry")
    return (0 until nodes.length).mapNotNull { idx -> nodes.item(idx) as? Element }
  }

  private fun insertBeforeOutput(root: Element, outputEntry: Element?, entries: List<Element>) {
    if (entries.isEmpty()) return
    if (outputEntry == null) {
      entries.forEach { root.appendChild(it) }
      return
    }
    entries.forEach { root.insertBefore(it, outputEntry) }
  }

  private fun toAbsPath(moduleDir: Path, raw: String): Path {
    val path = Paths.get(raw)
    return if (path.isAbsolute) path.normalize() else moduleDir.resolve(path).normalize()
  }

  private fun toSourcePath(moduleDir: Path, libPath: String, sourcePath: Path): String {
    val libIsAbsolute = Paths.get(libPath).isAbsolute
    return if (libIsAbsolute) sourcePath.toString() else moduleDir.relativize(sourcePath).toString()
  }

  private fun deriveBundleId(absLib: Path): String? {
    val parentName = absLib.parent?.fileName?.toString() ?: return null
    if (parentName.contains("_")) {
      return parentName.substringBefore("_")
    }
    val fileName = absLib.fileName.toString()
    if (!fileName.endsWith(".jar")) return null
    return fileName.substringBeforeLast("_")
  }

  private fun resolveSourceBundle(pluginPool: Path, absLib: Path): Path? {
    val parentName = absLib.parent?.fileName?.toString() ?: return null
    val fileName = absLib.fileName.toString()
    if (!fileName.endsWith(".jar")) return null
    return if (parentName.contains("_")) {
      val bundleId = parentName.substringBefore("_")
      val version = parentName.substringAfter("_", "")
      if (version.isNotBlank()) pluginPool.resolve("$bundleId.source_$version.jar") else null
    } else if (fileName.contains("_")) {
      val bundleId = fileName.substringBefore("_")
      val version = fileName.substringAfter("_", "").removeSuffix(".jar")
      if (version.isNotBlank()) pluginPool.resolve("$bundleId.source_$version.jar") else null
    } else null
  }

  private fun attachSourcesToLibEntries(
    root: Element,
    moduleDir: Path,
    pluginPool: Path?,
    localSourceZips: Map<String, Path>
  ) {
    val currentEntries = classpathEntries(root).filter { it.getAttribute("kind") == "lib" }
    currentEntries.forEach { entry ->
      if (!entry.getAttribute("sourcepath").isNullOrBlank()) return@forEach
      val path = entry.getAttribute("path") ?: return@forEach
      val absLib = toAbsPath(moduleDir, path)
      if (!Files.exists(absLib)) return@forEach

      val siblingSource = absLib.parent.resolve(absLib.fileName.toString().removeSuffix(".jar") + "-sources.jar")
      if (Files.exists(siblingSource)) {
        entry.setAttribute("sourcepath", toSourcePath(moduleDir, path, siblingSource))
        return@forEach
      }

      val bundleId = deriveBundleId(absLib)
      val localSource = bundleId?.let { localSourceZips[it] }
      if (localSource != null && Files.exists(localSource)) {
        entry.setAttribute("sourcepath", localSource.toString())
        return@forEach
      }

      if (pluginPool != null && absLib.startsWith(pluginPool)) {
        val sourceBundle = resolveSourceBundle(pluginPool, absLib)
        if (sourceBundle != null && Files.exists(sourceBundle)) {
          entry.setAttribute("sourcepath", sourceBundle.toString())
        }
      }
    }
  }

  private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).sorted(Comparator.reverseOrder()).forEach { entry ->
      Files.deleteIfExists(entry)
    }
  }

  private fun writeXml(doc: Document, path: Path) {
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
      setOutputProperty(OutputKeys.INDENT, "yes")
      setOutputProperty(OutputKeys.ENCODING, "UTF-8")
      setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    }
    BufferedOutputStream(Files.newOutputStream(path)).use { output ->
      transformer.transform(DOMSource(doc), StreamResult(output))
    }
  }
}
