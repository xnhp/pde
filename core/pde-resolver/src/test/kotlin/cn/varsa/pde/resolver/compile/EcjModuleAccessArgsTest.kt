package cn.varsa.pde.resolver.compile

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EcjModuleAccessArgsTest {

  @Test
  fun `add-exports token emitted as separate flag and value`() {
    val args = mutableListOf("-d", "out")
    EcjCompiler.appendModuleAccessFlags(
      args,
      addExports = listOf("java.security.jgss/sun.security.krb5=ALL-UNNAMED"),
      addOpens = emptyList()
    )
    val idx = args.indexOf("--add-exports")
    assertTrue(idx >= 0, "expected --add-exports flag")
    assertEquals("java.security.jgss/sun.security.krb5=ALL-UNNAMED", args[idx + 1])
  }

  @Test
  fun `multiple tokens each get their own flag`() {
    val args = mutableListOf<String>()
    EcjCompiler.appendModuleAccessFlags(
      args,
      addExports = listOf(
        "java.security.jgss/sun.security.krb5=ALL-UNNAMED",
        "java.security.jgss/sun.security.jgss=ALL-UNNAMED"
      ),
      addOpens = listOf("java.base/java.lang=ALL-UNNAMED")
    )
    assertEquals(
      listOf(
        "--add-exports", "java.security.jgss/sun.security.krb5=ALL-UNNAMED",
        "--add-exports", "java.security.jgss/sun.security.jgss=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
      ),
      args
    )
  }

  @Test
  fun `flags are inserted before source files in assembled args`() {
    val spec = baseSpec().copy(addExports = listOf("java.security.jgss/sun.security.krb5=ALL-UNNAMED"))
    val args = EcjCompiler.assembleArgs(spec, outDir = "out", sources = listOf("/ws/A.java"))

    val exportIdx = args.indexOf("--add-exports")
    val sourceIdx = args.indexOf("/ws/A.java")
    assertTrue(exportIdx >= 0, "expected --add-exports in args")
    assertTrue(sourceIdx >= 0, "expected source file in args")
    assertTrue(exportIdx < sourceIdx, "module-access flags must precede source files")
    assertEquals("java.security.jgss/sun.security.krb5=ALL-UNNAMED", args[exportIdx + 1])
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
