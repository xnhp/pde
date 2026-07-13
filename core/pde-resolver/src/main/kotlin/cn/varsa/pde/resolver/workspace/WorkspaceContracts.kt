package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Path

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClasspathEntry(
    val kind: String,
    val path: String,
    val sourcePath: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkspaceProjectSpec(
    val bsn: String,
    val version: String,
    val bundlePath: String,
    val sourceRoots: List<String> = emptyList(),
    val outputDirectory: String,
    val classpath: List<ClasspathEntry> = emptyList(),
    val compilerPrefs: Map<String, String> = emptyMap(),
    val executionEnvironment: String? = null,
    val dependencies: List<String> = emptyList(),
    val testBundle: Boolean = false
)

data class WorkspaceSetupInput(
    val projects: List<WorkspaceProjectSpec> = emptyList(),
    val targetClasspath: List<String> = emptyList()
)

object WorkspaceSetupInputJson {
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    fun write(input: WorkspaceSetupInput): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(input)

    fun read(path: Path): WorkspaceSetupInput =
        mapper.readValue(path.toFile(), WorkspaceSetupInput::class.java)
}

data class JdtBuildInput(
    val projects: List<String> = emptyList(),
    val fullRebuild: Boolean = false
)

object JdtBuildInputJson {
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    fun write(input: JdtBuildInput): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(input)

    fun read(path: Path): JdtBuildInput =
        mapper.readValue(path.toFile(), JdtBuildInput::class.java)
}

fun WorkspaceBundleDescriptor.toWorkspaceProjectSpec(
    version: String,
    dependencies: List<String>,
    testBundle: Boolean = false
): WorkspaceProjectSpec {
    val bsn = manifest.bundleSymbolicName?.key ?: error("Bundle lacks Bundle-SymbolicName")
    val relSourceRoots = sourceRoots.map { path.relativize(it).toString().replace('\\', '/') }
    val outputDir = (outputDirectory ?: path.resolve("bin")).let {
        path.relativize(it).toString().replace('\\', '/')
    }
    val libEntries = classPathEntries.map { entry ->
        ClasspathEntry(kind = "lib", path = entry.toAbsolutePath().normalize().toString())
    }
    return WorkspaceProjectSpec(
        bsn = bsn,
        version = version,
        bundlePath = path.toAbsolutePath().normalize().toString(),
        sourceRoots = relSourceRoots,
        outputDirectory = outputDir,
        classpath = libEntries,
        compilerPrefs = compilerPrefs,
        executionEnvironment = executionEnvironment,
        dependencies = dependencies,
        testBundle = testBundle
    )
}
