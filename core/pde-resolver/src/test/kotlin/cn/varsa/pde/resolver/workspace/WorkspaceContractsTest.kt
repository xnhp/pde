package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.algo.WorkspaceBundleDescriptor
import cn.varsa.pde.resolver.manifest.BundleManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME
import org.osgi.framework.Constants.BUNDLE_VERSION
import java.nio.file.Paths

class WorkspaceContractsTest {

    private fun bm(vararg pairs: Pair<String, String>) =
        BundleManifest.parse(mapOf(*pairs))

    @Test
    fun `round-trip WorkspaceSetupInput through JSON`() {
        val input = WorkspaceSetupInput(
            projects = listOf(
                WorkspaceProjectSpec(
                    bsn = "org.example.foo",
                    version = "1.0.0",
                    bundlePath = "/workspace/org.example.foo",
                    sourceRoots = listOf("src", "src/main/java"),
                    outputDirectory = "bin",
                    classpath = listOf(
                        ClasspathEntry(kind = "lib", path = "/target/lib/slf4j-api.jar", sourcePath = "/target/lib/slf4j-api-sources.jar"),
                        ClasspathEntry(kind = "lib", path = "/target/lib/junit.jar")
                    ),
                    compilerPrefs = mapOf(
                        "org.eclipse.jdt.core.compiler.source" to "17",
                        "org.eclipse.jdt.core.compiler.codegen.targetPlatform" to "17"
                    ),
                    executionEnvironment = "JavaSE-17",
                    dependencies = listOf("org.example.bar"),
                    testBundle = false
                ),
                WorkspaceProjectSpec(
                    bsn = "org.example.bar",
                    version = "2.0.0",
                    bundlePath = "/workspace/org.example.bar",
                    sourceRoots = listOf("src"),
                    outputDirectory = "bin",
                    classpath = emptyList(),
                    compilerPrefs = emptyMap(),
                    executionEnvironment = null,
                    dependencies = emptyList(),
                    testBundle = true
                )
            ),
            targetClasspath = listOf("/target/plugins/org.eclipse.osgi_3.18.0.jar")
        )

        val json = WorkspaceSetupInputJson.write(input)
        assertTrue("JSON output must contain bsn", json.contains("org.example.foo"))
        assertTrue("JSON output must contain targetClasspath", json.contains("org.eclipse.osgi"))

        val deserialized = WorkspaceSetupInputJson.read(
            Paths.get("/tmp/roundtrip.json").also {
                it.toFile().writeText(json)
            }
        )
        assertEquals(input, deserialized)
    }

    @Test
    fun `toWorkspaceProjectSpec maps descriptor fields`() {
        val bundlePath = Paths.get("/workspace/org.example.foo").toAbsolutePath().normalize()
        val descriptor = WorkspaceBundleDescriptor(
            path = bundlePath,
            manifest = bm(
                BUNDLE_SYMBOLICNAME to "org.example.foo",
                BUNDLE_VERSION to "1.0.0"
            ),
            classPathEntries = listOf(
                bundlePath.resolve("bin"),
                Paths.get("/target/lib/commons-lang3.jar")
            ),
            sourceRoots = listOf(
                bundlePath.resolve("src"),
                bundlePath.resolve("src/main/java")
            ),
            outputDirectory = bundlePath.resolve("bin"),
            compilerPrefs = mapOf("org.eclipse.jdt.core.compiler.source" to "17"),
            executionEnvironment = "JavaSE-17"
        )

        val spec = descriptor.toWorkspaceProjectSpec(
            version = "1.0.0",
            dependencies = listOf("org.example.bar", "org.example.baz"),
            testBundle = false
        )

        assertEquals("org.example.foo", spec.bsn)
        assertEquals("1.0.0", spec.version)
        assertEquals(bundlePath.toString(), spec.bundlePath)
        assertEquals(listOf("src", "src/main/java"), spec.sourceRoots)
        assertEquals("bin", spec.outputDirectory)
        assertEquals(2, spec.classpath.size)
        assertEquals("lib", spec.classpath[0].kind)
        assertEquals("lib", spec.classpath[1].kind)
        assertEquals(mapOf("org.eclipse.jdt.core.compiler.source" to "17"), spec.compilerPrefs)
        assertEquals("JavaSE-17", spec.executionEnvironment)
        assertEquals(listOf("org.example.bar", "org.example.baz"), spec.dependencies)
        assertEquals(false, spec.testBundle)
    }
}
