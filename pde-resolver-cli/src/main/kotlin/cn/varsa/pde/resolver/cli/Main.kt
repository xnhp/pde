package cn.varsa.pde.resolver.cli

import cn.varsa.pde.resolver.algo.ResolveOptions
import cn.varsa.pde.resolver.algo.Resolver
import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.index.TargetPlatformCache
import cn.varsa.pde.resolver.manifest.BundleManifest
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

fun main(args: Array<String>) {
  val parser = ArgParser("pde-resolver")

  val rootsOpt by parser.option(ArgType.String, fullName = "root", shortName = "r", description = "Root path (repeatable)").multiple()
  val cacheFilePath by parser.option(ArgType.String, fullName = "cache-file", shortName = "c", description = "Cache file path (optional)"
  )
  val filterBsn by parser.option(ArgType.String, fullName = "bsn", shortName = "b", description = "Filter by bundle symbolic name"
  )
  val json by parser.option(ArgType.Boolean, fullName = "json", shortName = "j", description = "Output JSON").default(false)
  val workspaceOpt by parser.option(ArgType.String, fullName = "workspace", shortName = "w", description = "Workspace bundle path (repeatable)").multiple()
  val plan by parser.option(ArgType.Boolean, fullName = "plan", description = "Resolve workspace bundles and show plan").default(false)

  parser.parse(args)

  val roots = rootsOpt
  if (roots.isEmpty()) {
    System.err.println("No roots provided. Pass one or more paths.")
    return
  }

  val paths: List<Path> = roots.map { File(it).toPath() }
  val cacheFile = cacheFilePath?.let { File(it) }
  val index = TargetPlatformCache.buildWithCache(paths, cacheFile)

  if (plan) {
    val workspacePaths = workspaceOpt.map { Paths.get(it) }
    if (workspacePaths.isEmpty()) {
      System.err.println("No workspace paths provided (--workspace). Plan mode requires at least one workspace bundle root.")
      return
    }

    val workspaceDescriptors = workspacePaths.mapNotNull { path ->
      val manifest = loadManifest(path)
      if (manifest == null) {
        System.err.println("Warning: unable to read manifest under $path; skipping")
        null
      } else {
        WorkspaceBundleDescriptor(path, manifest)
      }
    }
    if (workspaceDescriptors.isEmpty()) {
      System.err.println("No workspace manifests could be loaded.")
      return
    }

    val options = ResolveOptions(preferWorkspace = true, includeHostsForFragments = true)
    val results = workspaceDescriptors.map { desc -> desc to Resolver.resolve(index, workspaceDescriptors, desc, options) }
    val unresolved = results.flatMap { it.second.unresolved }

    val unique = LinkedHashMap<String, Pair<ResolvedView, Boolean>>()
    results.forEach { (_, res) ->
      res.bundles.forEach { rb ->
        val key = "${rb.bsn}|${rb.version}|${rb.path}"
        unique.putIfAbsent(key, ResolvedView(rb.bsn, rb.version.toString(), rb.path.toString()) to rb.isWorkspace)
      }
    }

    val flat = unique.values
      .map { pair -> pair }
      .filter { filterBsn == null || it.first.bsn == filterBsn }

    if (json) {
      val items = flat.joinToString(",") { (view, isWs) ->
        "{" +
          "\"bsn\":\"${escape(view.bsn)}\"," +
          "\"version\":\"${escape(view.version)}\"," +
          "\"path\":\"${escape(view.path)}\"," +
          "\"workspace\":$isWs" +
        "}"
      }
      println("{" +
        "\"count\":${flat.size}," +
        "\"bundles\":[" + items + "]" +
      "}")
    } else {
      println("Resolved bundles: ${flat.size}")
      flat.forEach { (view, isWs) ->
        val marker = if (isWs) "[workspace]" else "[target]"
        println("${view.bsn}@${view.version} -> ${view.path} $marker")
      }
      if (unresolved.isNotEmpty()) {
        System.err.println("Unresolved requirements:")
        unresolved.forEach { System.err.println(" - ${it.bsn} ${it.range ?: ""} (${it.reason})") }
      }
    }
  } else {
    val bundles = index.bundlesByBsn()
    val flat = bundles.entries
      .sortedBy { it.key }
      .flatMap { (bsn, nav) -> nav.entries.map { e -> Triple(bsn, e.key.toString(), e.value.location.toString()) } }
      .let { list -> if (filterBsn != null) list.filter { it.first == filterBsn } else list }

    if (json) {
      val items = flat.joinToString(",") { (bsn, ver, path) ->
        "{" +
          "\"bsn\":\"${escape(bsn)}\"," +
          "\"version\":\"${escape(ver)}\"," +
          "\"path\":\"${escape(path)}\"" +
        "}"
      }
      val src = index.source.name
      println("{" +
        "\"source\":\"$src\"," +
        "\"count\":${flat.size}," +
        "\"bundles\":[" + items + "]" +
      "}")
    } else {
      println("Source: ${index.source}; bundles: ${flat.size}")
      flat.forEach { (bsn, ver, path) -> println("$bsn@$ver -> $path") }
    }
  }
}

private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

private fun loadManifest(path: Path): BundleManifest? {
  val file = path.toFile()
  return when {
    file.isDirectory -> {
      val mf = File(file, "META-INF/MANIFEST.MF")
      if (!mf.exists()) return null
      mf.inputStream().use { BundleManifest.parse(java.util.jar.Manifest(it)) }
    }
    file.isFile -> JarFile(file).use { jar ->
      val entry = jar.getJarEntry("META-INF/MANIFEST.MF") ?: return null
      jar.getInputStream(entry).use { BundleManifest.parse(java.util.jar.Manifest(it)) }
    }
    else -> null
  }
}

private data class ResolvedView(val bsn: String, val version: String, val path: String)
