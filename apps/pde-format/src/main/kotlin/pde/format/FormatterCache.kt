package pde.format

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

data class CacheKey(
    val eclipseHome: Path,
    val profilePath: Path,
    val profileMtime: Long,
    val sourceLevel: String,
    val lineSeparator: String
)

data class CachedFormatter(val key: CacheKey, val formatter: EclipseFormatter)

object FormatterCache {
    private val cached = AtomicReference<CachedFormatter?>()

    fun getOrCreate(options: Options): EclipseFormatter {
        val profileMtime = Files.getLastModifiedTime(options.profile).toMillis()
        val key = CacheKey(
            eclipseHome = options.eclipseHome.toAbsolutePath().normalize(),
            profilePath = options.profile.toAbsolutePath().normalize(),
            profileMtime = profileMtime,
            sourceLevel = options.sourceLevel,
            lineSeparator = options.lineSeparator
        )

        val current = cached.get()
        if (current != null && current.key == key) {
            return current.formatter
        }

        val classLoader = EclipseJars.classLoaderFor(options.eclipseHome)
        val profileOptions = ProfileParser.loadOptions(options.profile)
        val defaults = FormatterDefaults.load(classLoader)
        val optionsMap = OptionsMerger.merge(defaults, profileOptions)
        OptionsInjector.injectCompilerLevel(optionsMap, options.sourceLevel)
        val formatter = EclipseFormatter(classLoader, optionsMap, options.lineSeparator)

        cached.set(CachedFormatter(key, formatter))
        return formatter
    }
}

object OptionsInjector {
    fun injectCompilerLevel(options: MutableMap<String, String>, level: String) {
        options["org.eclipse.jdt.core.compiler.source"] = level
        options["org.eclipse.jdt.core.compiler.codegen.targetPlatform"] = level
        options["org.eclipse.jdt.core.compiler.compliance"] = level
    }
}

object OptionsMerger {
    fun merge(defaults: Map<String, String>?, overrides: Map<String, String>): MutableMap<String, String> {
        val merged = mutableMapOf<String, String>()
        if (defaults != null) {
            merged.putAll(defaults)
        }
        merged.putAll(overrides)
        return merged
    }
}

object FormatterDefaults {
    fun load(classLoader: ClassLoader): Map<String, String>? {
        return runCatching {
            val optionsClass = classLoader.loadClass("org.eclipse.jdt.internal.formatter.DefaultCodeFormatterOptions")
            val getDefaultSettings = optionsClass.getMethod("getDefaultSettings")
            val defaults = getDefaultSettings.invoke(null)
            val mapMethod = defaults.javaClass.methods.firstOrNull {
                it.name == "getMap" && it.parameterCount == 0
            } ?: return@runCatching null
            val rawMap = mapMethod.invoke(defaults) as? Map<*, *> ?: return@runCatching null
            rawMap.entries.associate { it.key.toString() to it.value.toString() }
        }.getOrNull()
    }
}

object EclipseJars {
    fun classLoaderFor(eclipseHome: Path): URLClassLoader {
        val jars = EclipseJarLocator.locate(eclipseHome)
        val urls = jars.map { it.toUri().toURL() }.toTypedArray()
        return URLClassLoader(urls, ClassLoader.getSystemClassLoader())
    }
}

object EclipseJarLocator {
    fun locate(eclipseHome: Path): List<Path> {
        val pluginsDir = eclipseHome.resolve("plugins")
        val dropinsDir = eclipseHome.resolve("dropins")
        val jars = mutableListOf<Path>()

        if (Files.isDirectory(pluginsDir)) {
            jars += scanForJars(pluginsDir)
        }
        if (Files.isDirectory(dropinsDir)) {
            jars += scanForJars(dropinsDir)
        }

        if (jars.isEmpty()) {
            error("No Eclipse jars found in ${eclipseHome.toAbsolutePath()}")
        }
        return jars.distinct()
    }

    private fun scanForJars(dir: Path): List<Path> {
        val jars = mutableListOf<Path>()
        Files.walk(dir, 2).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
                .forEach { jars.add(it) }
        }
        return jars
    }
}
