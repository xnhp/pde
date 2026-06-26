package cn.varsa.pde.resolver.workspace

import cn.varsa.pde.resolver.launch.WorkspaceInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class WorkspaceModuleBuilderTest {

  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test(expected = WorkspaceModuleException::class)
  fun failsWhenModuleDirectoryMissing() {
    val missingDir = temp.root.toPath().resolve("missing-module")
    WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(missingDir)))
  }

  @Test(expected = WorkspaceModuleException::class)
  fun failsWhenManifestMissing() {
    val moduleDir = temp.newFolder("module-no-manifest").toPath()
    WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(moduleDir)))
  }

  @Test
  fun succeedsWhenManifestPresent() {
    val moduleDir = temp.newFolder("module-with-manifest").toPath()
    val metaInf = File(moduleDir.toFile(), "META-INF")
    metaInf.mkdirs()
    val manifest = File(metaInf, "MANIFEST.MF")
    manifest.writeText(
      "Bundle-ManifestVersion: 2\n" +
        "Bundle-SymbolicName: test.module\n" +
        "Bundle-Version: 1.0.0\n\n"
    )
    // Provide a class output directory that matches the default root and a dummy class file
    val outDir = File(moduleDir.toFile(), "bin")
    outDir.mkdirs()
    File(outDir, "Dummy.class").writeText("")

    val inputs: WorkspaceInputs = WorkspaceModuleBuilder.build(
      listOf(WorkspaceModuleDefinition(moduleDir))
    )

    assertEquals(1, inputs.descriptors.size)
    assertEquals("test.module", inputs.descriptors.first().manifest.bundleSymbolicName?.key)
  }

  @Test(expected = WorkspaceModuleException::class)
  fun failsWhenOutputsEmpty() {
    val moduleDir = temp.newFolder("module-empty-classes").toPath()
    val metaInf = File(moduleDir.toFile(), "META-INF")
    metaInf.mkdirs()
    File(metaInf, "MANIFEST.MF").writeText(
      "Bundle-ManifestVersion: 2\n" +
        "Bundle-SymbolicName: test.module\n" +
        "Bundle-Version: 1.0.0\n\n"
    )
    val outDir = File(moduleDir.toFile(), "bin")
    outDir.mkdirs()

    // Output directory exists but contains no .class files; should fail fast
    WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(moduleDir)))
  }

  @Test
  fun `derives class roots from build properties output when omitted`() {
    val moduleDir = createWorkspaceModule("module-derived-output", "test.module.derived")
    File(moduleDir.toFile(), "build.properties").writeText("output.. = bin/eclipse/\n")

    val inputs = WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(moduleDir)), allowMissingClasses = true)

    assertEquals(listOf("bin/eclipse"), inputs.devProperties["test.module.derived"])
  }

  @Test
  fun `falls back to default bin when output is not configured`() {
    val moduleDir = createWorkspaceModule("module-default-output", "test.module.default")

    val inputs = WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(moduleDir)), allowMissingClasses = true)

    assertEquals(listOf("bin"), inputs.devProperties["test.module.default"])
  }

  @Test
  fun `explicit class roots override derived output`() {
    val moduleDir = createWorkspaceModule("module-explicit-roots", "test.module.explicit")
    File(moduleDir.toFile(), "build.properties").writeText("output.. = bin/eclipse/\n")

    val inputs = WorkspaceModuleBuilder.build(
      listOf(WorkspaceModuleDefinition(moduleDir, classRoots = listOf("custom/classes"))),
      allowMissingClasses = true
    )

    assertEquals(listOf("custom/classes"), inputs.devProperties["test.module.explicit"])
  }

  @Test
  fun `logs info when class roots are auto derived`() {
    val moduleDir = createWorkspaceModule("module-log-derived", "test.module.log")
    File(moduleDir.toFile(), "build.properties").writeText("output.. = bin/eclipse/\n")
    val logger = Logger.getLogger(WorkspaceModuleBuilder::class.java.name)
    val records = mutableListOf<LogRecord>()
    val handler = object : Handler() {
      override fun publish(record: LogRecord) {
        if (record.level == Level.INFO) {
          records += record
        }
      }

      override fun flush() = Unit
      override fun close() = Unit
    }

    val previousLevel = logger.level
    val previousUseParent = logger.useParentHandlers
    logger.level = Level.INFO
    logger.useParentHandlers = false
    logger.addHandler(handler)
    try {
      WorkspaceModuleBuilder.build(listOf(WorkspaceModuleDefinition(moduleDir)), allowMissingClasses = true)
    } finally {
      logger.removeHandler(handler)
      logger.level = previousLevel
      logger.useParentHandlers = previousUseParent
    }

    val matching = records.filter {
      it.message.contains("Auto-derived classRoots for") && it.message.contains("from build.properties output..")
    }
    assertEquals(1, matching.size)
    assertTrue(matching.single().message.contains("[bin/eclipse]"))
  }

  @Test
  fun `appends classpath and explicit compiler args without deduplicating flags`() {
    val moduleDir = createWorkspaceModule("module-module-access", "test.module.access")
    File(moduleDir.toFile(), ".classpath").writeText(
      """
        <?xml version="1.0" encoding="UTF-8"?>
        <classpath>
          <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER/x/JavaSE-21">
            <attributes>
              <attribute name="add-exports" value="java.security.jgss/sun.security.krb5=ALL-UNNAMED"/>
            </attributes>
          </classpathentry>
          <classpathentry kind="src" path="src"/>
        </classpath>
      """.trimIndent()
    )

    val inputs = WorkspaceModuleBuilder.build(
      listOf(
        WorkspaceModuleDefinition(
          moduleDir,
          compilerArgs = listOf(
            "--add-exports",
            "java.security.jgss/sun.security.krb5=ALL-UNNAMED", // duplicate of .classpath token
            "--add-exports",
            "java.base/sun.net.util=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.lang=ALL-UNNAMED"
          )
        )
      ),
      allowMissingClasses = true
    )

    val desc = inputs.descriptors.single()
    assertEquals(
      listOf(
        "--add-exports",
        "java.security.jgss/sun.security.krb5=ALL-UNNAMED",
        "--add-exports",
        "java.security.jgss/sun.security.krb5=ALL-UNNAMED",
        "--add-exports",
        "java.base/sun.net.util=ALL-UNNAMED",
        "--add-opens",
        "java.base/java.lang=ALL-UNNAMED"
      ),
      desc.compilerArgs
    )
  }

  private fun createWorkspaceModule(dirName: String, bsn: String) = temp.newFolder(dirName).toPath().also { moduleDir ->
    val metaInf = File(moduleDir.toFile(), "META-INF")
    metaInf.mkdirs()
    File(metaInf, "MANIFEST.MF").writeText(
      "Bundle-ManifestVersion: 2\n" +
        "Bundle-SymbolicName: ${bsn}\n" +
        "Bundle-Version: 1.0.0\n\n"
    )
  }
}
