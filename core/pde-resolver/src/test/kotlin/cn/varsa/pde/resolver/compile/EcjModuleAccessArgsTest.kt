package cn.varsa.pde.resolver.compile

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EcjModuleAccessArgsTest {

  @Test
  fun `custom compiler args are inserted before source files in assembled args`() {
    val spec = baseSpec().copy(
      compilerArgs = listOf(
        "--add-exports",
        "java.security.jgss/sun.security.krb5=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED"
      )
    )
    val args = EcjCompiler.assembleArgs(spec, outDir = "out", sources = listOf("/ws/A.java"))

    val exportIdx = args.indexOf("--add-exports")
    val opensIdx = args.indexOf("--add-opens")
    val sourceIdx = args.indexOf("/ws/A.java")
    assertTrue(exportIdx >= 0, "expected --add-exports in args")
    assertTrue(opensIdx >= 0, "expected --add-opens in args")
    assertTrue(sourceIdx >= 0, "expected source file in args")
    assertTrue(exportIdx < sourceIdx, "custom compiler args must precede source files")
    assertTrue(opensIdx < sourceIdx, "custom compiler args must precede source files")
    assertEquals("java.security.jgss/sun.security.krb5=ALL-UNNAMED", args[exportIdx + 1])
    assertEquals("java.base/java.lang=ALL-UNNAMED", args[opensIdx + 1])
  }

  @Test
  fun `blank custom compiler args are ignored`() {
    val spec = baseSpec().copy(compilerArgs = listOf("", "  ", "--enable-preview"))
    val args = EcjCompiler.assembleArgs(spec, outDir = "out", sources = listOf("/ws/A.java"))

    assertEquals(1, args.count { it == "--enable-preview" })
    assertTrue("" !in args)
    assertTrue("  " !in args)
  }

  private fun baseSpec() = CompileSpec(
    bsn = "demo",
    version = "1.0.0",
    origin = "workspace",
    bundlePath = "/ws",
    classpath = emptyList(),
    sourceRoots = listOf("/ws/src"),
    resourceIncludes = emptyList(),
    resourceExcludes = emptyList(),
    compilerPrefs = mapOf("org.eclipse.jdt.core.compiler.source" to "17"),
    executionEnvironment = "JavaSE-17",
    outputDirectory = "/ws/bin",
    isWorkspace = true
  )
}
