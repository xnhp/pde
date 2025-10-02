package cn.varsa.pde.resolver.index

import cn.varsa.pde.resolver.manifest.BundleManifest
import org.osgi.framework.Version
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*

/**
 * Fingerprint + persisted snapshot (Properties) for TargetPlatformIndex.
 */
object TargetPlatformCache {
  private const val SCHEMA = "1"

  data class Snapshot(
    val fingerprint: String,
    val records: List<Record>,
  )

  data class Record(
    val path: String,
    val isDirectory: Boolean,
    val manifest: BundleManifest,
  )

  fun buildWithCache(roots: List<Path>, cacheFile: File? = null): TargetPlatformIndex {
    val fp = computeFingerprint(roots)
    val cache = cacheFile ?: defaultCacheFile(roots)
    val snapshot = runCatching { load(cache) }.getOrNull()
    if (snapshot != null && snapshot.fingerprint == fp) {
      return TargetPlatformIndex(reconstructMap(snapshot.records), TargetPlatformIndex.Source.CACHED)
    }

    val index = TargetPlatformIndex.build(roots)
    // save
    runCatching { save(cache, Snapshot(fp, flatten(index))) }
    return index
  }

  private fun defaultCacheFile(roots: List<Path>): File {
    val md = MessageDigest.getInstance("SHA-256")
    val key = roots.map { it.toAbsolutePath().normalize().toString() }.sorted().joinToString("|")
    val hex = md.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    val base = File(System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").absolutePath)
    val dir = File(base, "pde-resolver/v$SCHEMA")
    dir.mkdirs()
    return File(dir, "index-$hex.properties")
  }

  fun computeFingerprint(roots: List<Path>): String {
    val md = MessageDigest.getInstance("SHA-256")
    val lines = mutableListOf<String>()

    roots.forEach { rootPath ->
      val f = rootPath.toFile()
      when {
        File(f, "plugins").isDirectory ->
          collectSdkCandidates(f).forEach { lines += statLine(it) }
        f.isDirectory && f.name.endsWith(".profile", true) -> {
          val profileFile = findProfileFile(f) ?: return@forEach
          lines += statLine(profileFile)
          val bundlePoolPath = getBundlePoolPath(profileFile) ?: return@forEach
          val pool = File(bundlePoolPath.removePrefix("file:"))
          val pluginsDir = File(pool, "plugins")
          pluginsDir.listFiles()?.filterNot { it.isHidden }?.forEach { lines += statLine(it) }
        }
        f.isDirectory -> f.listFiles()?.filterNot { it.isHidden }?.forEach { lines += statLine(it) }
        f.isFile -> lines += statLine(f)
      }
    }

    lines.sort()
    lines.forEach { md.update(it.toByteArray()) }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  private fun collectSdkCandidates(root: File): List<File> {
    val out = mutableListOf<File>()
    val plugins = File(root, "plugins")
    plugins.listFiles()?.filterNot { it.isHidden }?.forEach(out::add)
    val dropins = File(root, "dropins")
    dropins.listFiles()?.filterNot { it.isHidden }?.forEach { d ->
      if (d.isDirectory) File(d, "plugins").listFiles()?.filterNot { it.isHidden }?.forEach(out::add)
    }
    return out
  }

  private fun statLine(file: File): String {
    val stat = file.takeIf { it.isFile } ?: File(file, "META-INF/MANIFEST.MF")
    val p = stat.absolutePath
    val size = stat.length()
    val mtime = stat.lastModified()
    return "$p:$size:$mtime"
  }

  private fun flatten(index: TargetPlatformIndex): List<Record> {
    val recs = mutableListOf<Record>()
    index.bundlesByBsn().values.forEach { nav ->
      nav.values.forEach { rb ->
        recs += Record(rb.location.toString(), rb.isDirectory, rb.manifest)
      }
    }
    return recs
  }

  private fun reconstructMap(records: List<Record>): Map<String, NavigableMap<Version, ResolvedBundle>> {
    val map = HashMap<String, NavigableMap<Version, ResolvedBundle>>()
    records.forEach { r ->
      val rb = ResolvedBundle(File(r.path).toPath(), r.manifest, r.isDirectory)
      val bsn = r.manifest.bundleSymbolicName?.key ?: return@forEach
      map.computeIfAbsent(bsn) { TreeMap() }[r.manifest.bundleVersion] = rb
    }
    return map
  }

  fun load(file: File): Snapshot {
    val props = Properties()
    FileInputStream(file).use { props.load(it) }
    if (props.getProperty("schema") != SCHEMA) error("schema mismatch")
    val fp = props.getProperty("fingerprint") ?: error("missing fingerprint")
    val count = props.getProperty("count")?.toIntOrNull() ?: 0
    val recs = (0 until count).map { i ->
      val path = props.getProperty("record.$i.path") ?: ""
      val isDir = props.getProperty("record.$i.dir")?.toBoolean() ?: false
      val manifest = readManifestProps(props, i)
      Record(path, isDir, manifest)
    }
    return Snapshot(fp, recs)
  }

  fun save(file: File, snapshot: Snapshot) {
    val props = Properties()
    props["schema"] = SCHEMA
    props["fingerprint"] = snapshot.fingerprint
    props["count"] = snapshot.records.size.toString()
    snapshot.records.forEachIndexed { i, r ->
      props["record.$i.path"] = r.path
      props["record.$i.dir"] = r.isDirectory.toString()
      writeManifestProps(props, i, r.manifest)
    }
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(tmp).use { props.store(it, "pde-resolver target index") }
    if (!tmp.renameTo(file)) {
      // Best-effort fallback overwrite
      FileOutputStream(file).use { props.store(it, "pde-resolver target index") }
      tmp.delete()
    }
  }

  private fun writeManifestProps(props: Properties, idx: Int, bm: BundleManifest) {
    val prefix = "record.$idx.manifest."
    props["$prefix.size"] = bm.size.toString()
    var j = 0
    bm.forEach { (k, v) ->
      props["$prefix.key.$j"] = k
      props["$prefix.val.$j"] = v
      j++
    }
  }

  private fun readManifestProps(props: Properties, idx: Int): BundleManifest {
    val prefix = "record.$idx.manifest."
    val size = props.getProperty("$prefix.size")?.toIntOrNull() ?: 0
    val map = HashMap<String, String>(size)
    for (j in 0 until size) {
      val k = props.getProperty("$prefix.key.$j") ?: continue
      val v = props.getProperty("$prefix.val.$j") ?: ""
      map[k] = v
    }
    return BundleManifest.parse(map)
  }
}

