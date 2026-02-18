package cn.varsa.pde.launch

import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.Resolver
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.cli.config.LaunchConfigContext
import cn.varsa.pde.resolver.cli.config.LaunchConfigLoader
import cn.varsa.pde.resolver.cli.config.WorkspaceModuleResolver
import cn.varsa.pde.resolver.index.TargetPlatformCache
import cn.varsa.pde.resolver.index.TargetPlatformIndex
import cn.varsa.pde.resolver.workspace.WorkspaceBundleLoader
import cn.varsa.pde.resolver.workspace.WorkspaceDefaults
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.optional
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object JdtlsInitCommand {
  fun main(args: Array<String>): Int {
    val parser = ArgParser("pde jdtls-init")
    val configOpt by parser.option(
      ArgType.String,
      fullName = "config",
      description = "YAML launch configuration path"
    )
    val issueDirOpt by parser.option(
      ArgType.String,
      fullName = "issue-dir",
      description = "Issue directory containing config.yaml and repos"
    )
    val force by parser.option(
      ArgType.Boolean,
      fullName = "force",
      description = "Overwrite existing .project/.classpath"
    ).default(false)
    val projectConfigurationsOut by parser.option(
      ArgType.String,
      fullName = "project-configurations-out",
      description = "Write JDT LS projectConfigurations JSON to file"
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
      System.err.println("Config file not found: ${explicitConfig.toAbsolutePath().normalize()}")
      return 1
    }
    if (configPath == null) {
      System.err.println("No launch config found (config.yaml/launch.yaml/pde.yaml). Use --config.")
      return 1
    }

    return try {
      val workingDir = issueDirOpt?.let { Paths.get(it) }
        ?: configPath.parent
        ?: issueDir
      val context = LaunchConfigLoader.load(configPath, workingDir)
      val workspaceInputs = WorkspaceModuleResolver.resolve(context, allowMissingClasses = true)
      val targetIndex = resolveTargetIndex(context)
      val result = writeWorkspaceConfigs(context, workspaceInputs.descriptors, targetIndex, force)
      println("Generated .project/.classpath for ${result.written} workspace bundles.")
      val projectConfigurationsPath = projectConfigurationsOut?.let { resolvePath(context.baseDir, it) }
      if (projectConfigurationsPath != null) {
        writeProjectConfigurationsOutput(projectConfigurationsPath, result.projectConfigurations)
        println("Wrote projectConfigurations to ${projectConfigurationsPath.toAbsolutePath().normalize()}")
      }
      0
    } catch (ex: Exception) {
      System.err.println(ex.message ?: "jdtls-init failed")
      1
    }
  }
}

private data class WorkspaceConfigResult(
  val written: Int,
  val projectConfigurations: List<Path>
)

private fun writeWorkspaceConfigs(
  context: LaunchConfigContext,
  workspaceDescriptors: List<WorkspaceBundleDescriptor>,
  targetIndex: TargetPlatformIndex,
  force: Boolean
): WorkspaceConfigResult {
  val modules = context.config.workspaceModules
  if (modules.isEmpty()) {
    fail("No workspaceModules configured; add bundlesPerRepo or workspaceModules to your config.")
  }
  val descriptorByPath = workspaceDescriptors.associateBy { it.path.toAbsolutePath().normalize() }
  val projectNameByBsn = workspaceDescriptors.associate {
    val bsn = it.manifest.bundleSymbolicName?.key ?: it.path.fileName.toString()
    bsn to bsn
  }
  var written = 0
  val projectConfigurations = LinkedHashSet<Path>()
  modules.forEach { module ->
    val moduleDir = resolvePath(context.baseDir, module.path)
    if (!Files.exists(moduleDir) || !Files.isDirectory(moduleDir)) {
      fail("Workspace bundle directory does not exist: ${moduleDir}")
    }
    val descriptor = descriptorByPath[moduleDir.toAbsolutePath().normalize()] ?: WorkspaceBundleLoader.load(moduleDir)
    val bundleName = descriptor.manifest.bundleSymbolicName?.key ?: moduleDir.fileName.toString()
    val isTestBundle = isTestBundle(bundleName, moduleDir, descriptor.fragmentHost != null)
    val sourceRoots = determineSourceRoots(moduleDir, descriptor.sourceRoots)
    val outputDir = descriptor.outputDirectory ?: moduleDir.resolve(WorkspaceDefaults.DEFAULT_OUTPUT_DIR)
    val outputPath = relativizeOrDefault(moduleDir, outputDir, WorkspaceDefaults.DEFAULT_OUTPUT_DIR)
    val compliance = resolveJavaCompliance(descriptor.compilerPrefs)
    val resolved = Resolver.resolve(
      targetIndex,
      workspaceDescriptors,
      descriptor,
      ResolveOptions(preferWorkspace = true, includeHostsForFragments = true)
    )
    val resolvedEntries = buildResolvedClasspathEntries(
      bundleName,
      resolved.bundles,
      projectNameByBsn
    )

    val projectFile = moduleDir.resolve(".project")
    val projectWritten = writeProjectFile(moduleDir, bundleName, force)
    val classpathWritten = writeClasspathFile(
      moduleDir,
      sourceRoots,
      resolvedEntries,
      outputPath,
      isTestBundle,
      compliance,
      force
    )
    if (projectWritten || classpathWritten) {
      written += 1
    }
    if (Files.exists(projectFile)) {
      projectConfigurations.add(projectFile.toAbsolutePath().normalize())
    }
  }
  return WorkspaceConfigResult(written, projectConfigurations.toList())
}

private fun writeProjectFile(moduleDir: Path, projectName: String, force: Boolean): Boolean {
  val projectFile = moduleDir.resolve(".project")
  if (!force && Files.exists(projectFile)) return false
  val builder = StringBuilder()
  builder.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
  builder.appendLine("<projectDescription>")
  builder.appendLine("  <name>${xmlEscape(projectName)}</name>")
  builder.appendLine("  <comment></comment>")
  builder.appendLine("  <projects></projects>")
  builder.appendLine("  <buildSpec>")
  builder.appendLine("    <buildCommand>")
  builder.appendLine("      <name>org.eclipse.jdt.core.javabuilder</name>")
  builder.appendLine("      <arguments></arguments>")
  builder.appendLine("    </buildCommand>")
  builder.appendLine("    <buildCommand>")
  builder.appendLine("      <name>org.eclipse.pde.ManifestBuilder</name>")
  builder.appendLine("      <arguments></arguments>")
  builder.appendLine("    </buildCommand>")
  builder.appendLine("    <buildCommand>")
  builder.appendLine("      <name>org.eclipse.pde.SchemaBuilder</name>")
  builder.appendLine("      <arguments></arguments>")
  builder.appendLine("    </buildCommand>")
  builder.appendLine("  </buildSpec>")
  builder.appendLine("  <natures>")
  builder.appendLine("    <nature>org.eclipse.pde.PluginNature</nature>")
  builder.appendLine("    <nature>org.eclipse.jdt.core.javanature</nature>")
  builder.appendLine("  </natures>")
  builder.appendLine("</projectDescription>")
  Files.writeString(projectFile, builder.toString(), StandardCharsets.UTF_8)
  return true
}

private fun writeClasspathFile(
  moduleDir: Path,
  sourceRoots: List<Path>,
  resolvedEntries: List<ClasspathEntry>,
  outputPath: String,
  isTestBundle: Boolean,
  compliance: String,
  force: Boolean
): Boolean {
  val classpathFile = moduleDir.resolve(".classpath")
  if (!force && Files.exists(classpathFile)) return false
  val builder = StringBuilder()
  builder.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
  builder.appendLine("<classpath>")
  sourceRoots.forEach { root ->
    val relative = relativizeOrDefault(moduleDir, root, root.toString())
    builder.appendLine("  <classpathentry kind=\"src\" path=\"${xmlEscape(relative)}\">")
    if (isTestBundle) {
      builder.appendLine("    <attributes>")
      builder.appendLine("      <attribute name=\"test\" value=\"true\"/>")
      builder.appendLine("    </attributes>")
    }
    builder.appendLine("  </classpathentry>")
  }
  val jreContainer = "org.eclipse.jdt.launching.JRE_CONTAINER/" +
    "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-${compliance}"
  builder.appendLine("  <classpathentry kind=\"con\" path=\"${jreContainer}\"/>")
  resolvedEntries.forEach { entry ->
    val sourceAttr = entry.sourcePath?.let { " sourcepath=\"${xmlEscape(it)}\"" } ?: ""
    builder.appendLine("  <classpathentry kind=\"${entry.kind}\" path=\"${xmlEscape(entry.path)}\"${sourceAttr}/>")
  }
  builder.appendLine("  <classpathentry kind=\"output\" path=\"${xmlEscape(outputPath)}\"/>")
  builder.appendLine("</classpath>")
  Files.writeString(classpathFile, builder.toString(), StandardCharsets.UTF_8)
  return true
}

private fun writeProjectConfigurationsOutput(outputPath: Path, projectConfigurations: List<Path>) {
  if (outputPath.parent != null) {
    Files.createDirectories(outputPath.parent)
  }
  val uris = projectConfigurations.map { it.toAbsolutePath().normalize().toUri().toString() }.distinct()
  val builder = StringBuilder()
  builder.appendLine("{")
  builder.appendLine("  \"projectConfigurations\": [")
  uris.forEachIndexed { index, uri ->
    val suffix = if (index == uris.size - 1) "" else ","
    builder.append("    \"").append(jsonEscape(uri)).append("\"").append(suffix).appendLine()
  }
  builder.appendLine("  ]")
  builder.appendLine("}")
  Files.writeString(outputPath, builder.toString(), StandardCharsets.UTF_8)
}

private data class ClasspathEntry(val kind: String, val path: String, val sourcePath: String? = null)

private fun buildResolvedClasspathEntries(
  bundleName: String,
  resolvedBundles: List<cn.varsa.pde.resolver.algo.ResolvedBundle>,
  projectNameByBsn: Map<String, String>
): List<ClasspathEntry> {
  val entries = LinkedHashMap<String, ClasspathEntry>()
  resolvedBundles.forEach { bundle ->
    if (bundle.bsn == bundleName) return@forEach
    when (bundle.origin) {
      cn.varsa.pde.resolver.algo.BundleOrigin.WORKSPACE -> {
        val projectName = projectNameByBsn[bundle.bsn] ?: bundle.bsn
        val path = "/${projectName}"
        entries.putIfAbsent(path, ClasspathEntry("src", path))
      }
      cn.varsa.pde.resolver.algo.BundleOrigin.TARGET -> {
        bundle.classPathEntries.forEach { classPathEntry ->
          val path = classPathEntry.toAbsolutePath().normalize().toString()
        val sourcePath = bundle.sourceEntries.firstOrNull()?.toAbsolutePath()?.normalize()?.toString()
        entries.putIfAbsent(path, ClasspathEntry("lib", path, sourcePath))
        }
      }
    }
  }
  return entries.values.toList()
}

private fun resolveConfigPath(baseDir: Path, configOpt: String?, configPos: String?): Path? {
  val candidate = configOpt ?: configPos?.takeIf { looksLikeYamlFile(it) }
  return candidate?.let { resolvePath(baseDir, it) }
}

private fun resolvePath(baseDir: Path, raw: String): Path {
  val path = Paths.get(raw)
  return if (path.isAbsolute) path else baseDir.resolve(path).normalize()
}

private fun findConfigPath(startDir: Path): Path? {
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

private fun looksLikeYamlFile(value: String): Boolean =
  value.endsWith(".yaml", ignoreCase = true) || value.endsWith(".yml", ignoreCase = true)

private fun determineSourceRoots(moduleDir: Path, configured: List<Path>): List<Path> {
  val existingConfigured = configured.filter { Files.exists(it) && Files.isDirectory(it) }
  if (existingConfigured.isNotEmpty()) return existingConfigured
  val srcDir = moduleDir.resolve("src")
  if (!Files.isDirectory(srcDir)) return emptyList()
  val roots = mutableListOf<Path>()
  val eclipseDir = srcDir.resolve("eclipse")
  if (Files.isDirectory(eclipseDir)) roots.add(eclipseDir)
  val generatedDir = srcDir.resolve("generated")
  if (Files.isDirectory(generatedDir)) roots.add(generatedDir)
  if (roots.isEmpty()) roots.add(srcDir)
  return roots
}

private fun resolveJavaCompliance(prefs: Map<String, String>): String {
  val target = prefs["org.eclipse.jdt.core.compiler.codegen.targetPlatform"]?.trim().orEmpty()
  if (target.isNotEmpty()) return target
  val compliance = prefs["org.eclipse.jdt.core.compiler.compliance"]?.trim().orEmpty()
  if (compliance.isNotEmpty()) return compliance
  return "21"
}

private fun isTestBundle(symbolicName: String, moduleDir: Path, hasFragmentHost: Boolean): Boolean {
  val name = symbolicName.lowercase()
  val dirName = moduleDir.fileName.toString().lowercase()
  val testHint = name.contains(".test") || name.contains(".tests") || name.contains(".testing") ||
    dirName.contains("test") || dirName.contains("tests")
  return testHint
}

private fun resolveTargetIndex(context: LaunchConfigContext): TargetPlatformIndex {
  val profilePath = resolveProfilePath(context)
  if (profilePath != null && Files.exists(profilePath)) {
    return TargetPlatformCache.buildWithCache(listOf(profilePath))
  }
  val fallbackRoots = resolveTargetFallbackRoots(context)
  if (fallbackRoots.isNotEmpty()) {
    return TargetPlatformCache.buildWithCache(fallbackRoots)
  }
  return TargetPlatformIndex.build(emptyList())
}

private fun resolveTargetFallbackRoots(context: LaunchConfigContext): List<Path> {
  val targetConfig = context.config.target ?: return emptyList()
  val baseDir = context.baseDir
  val roots = mutableListOf<Path>()
  targetConfig.bundlePool?.takeUnless { it.isBlank() }?.let { roots.add(resolvePath(baseDir, it)) }
  targetConfig.install?.takeUnless { it.isBlank() }?.let { roots.add(resolvePath(baseDir, it)) }
  return roots.filter { Files.exists(it) }
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
  if (Files.exists(lowercase)) return lowercase
  return preferred
}

private fun relativizeOrDefault(baseDir: Path, path: Path, fallback: String): String {
  return if (path.startsWith(baseDir)) {
    baseDir.relativize(path).toString().replace('\\', '/')
  } else {
    fallback
  }
}

private fun xmlEscape(value: String): String {
  return value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
}

private fun jsonEscape(value: String): String {
  return value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
}

private fun fail(message: String): Nothing = throw IOException(message)
