package cn.varsa.pde.launch

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LspIgnoreTest {

  private val originalRunner = LspIgnoreCommand.gitUpdateIndexRunner

  @AfterTest
  fun restoreRunner() {
    LspIgnoreCommand.gitUpdateIndexRunner = originalRunner
  }

  private data class Invocation(val moduleDir: Path, val flag: String, val files: List<String>)

  // ── ignore / unignore invoke git with the expected files ───────────────────

  @Test
  fun `lsp ignore invokes git update-index skip-worktree with expected relative paths`() {
    val baseDir = Files.createTempDirectory("lsp-ignore-happy-path")
    val bundleA = writeBundleFiles(baseDir.resolve("org.example.a"), project = true, classpath = true, prefs = listOf("org.eclipse.jdt.core.prefs"))
    val bundleB = writeBundleFiles(baseDir.resolve("org.example.b"), project = true, classpath = true, prefs = listOf("org.eclipse.jdt.core.prefs"))
    val configPath = writeConfig(baseDir, listOf(bundleA, bundleB))

    val invocations = mutableListOf<Invocation>()
    LspIgnoreCommand.gitUpdateIndexRunner = { moduleDir, flag, files ->
      invocations += Invocation(moduleDir, flag, files)
      true
    }

    val exitCode = LspIgnoreCommand.main(arrayOf("--config", configPath.toString()))

    assertEquals(0, exitCode)
    assertEquals(2, invocations.size)
    invocations.forEach { invocation ->
      assertEquals("--skip-worktree", invocation.flag)
      assertEquals(
        setOf(".project", ".classpath", ".settings/org.eclipse.jdt.core.prefs"),
        invocation.files.toSet()
      )
    }
    assertEquals(setOf(bundleA, bundleB), invocations.map { it.moduleDir }.toSet())
  }

  @Test
  fun `lsp unignore invokes git update-index with no-skip-worktree`() {
    val baseDir = Files.createTempDirectory("lsp-unignore-happy-path")
    val bundle = writeBundleFiles(baseDir.resolve("org.example.a"), project = true, classpath = true, prefs = listOf("org.eclipse.jdt.core.prefs"))
    val configPath = writeConfig(baseDir, listOf(bundle))

    val invocations = mutableListOf<Invocation>()
    LspIgnoreCommand.gitUpdateIndexRunner = { moduleDir, flag, files ->
      invocations += Invocation(moduleDir, flag, files)
      true
    }

    val exitCode = LspIgnoreCommand.mainUndo(arrayOf("--config", configPath.toString()))

    assertEquals(0, exitCode)
    assertEquals(1, invocations.size)
    assertEquals("--no-skip-worktree", invocations.single().flag)
  }

  // ── partial file sets ────────────────────────────────────────────────────

  @Test
  fun `bundle missing some files only includes files that exist`() {
    val baseDir = Files.createTempDirectory("lsp-ignore-partial")
    // Only .project exists; no .classpath, no .settings dir at all.
    val bundle = writeBundleFiles(baseDir.resolve("org.example.a"), project = true, classpath = false, prefs = emptyList())
    val configPath = writeConfig(baseDir, listOf(bundle))

    val invocations = mutableListOf<Invocation>()
    LspIgnoreCommand.gitUpdateIndexRunner = { moduleDir, flag, files ->
      invocations += Invocation(moduleDir, flag, files)
      true
    }

    val exitCode = LspIgnoreCommand.main(arrayOf("--config", configPath.toString()))

    assertEquals(0, exitCode)
    assertEquals(1, invocations.size)
    assertEquals(listOf(".project"), invocations.single().files)
  }

  @Test
  fun `bundle with no candidate files is skipped without invoking git`() {
    val baseDir = Files.createTempDirectory("lsp-ignore-no-files")
    val bundle = baseDir.resolve("org.example.empty")
    Files.createDirectories(bundle)
    val configPath = writeConfig(baseDir, listOf(bundle))

    var invoked = false
    LspIgnoreCommand.gitUpdateIndexRunner = { _, _, _ -> invoked = true; true }

    val exitCode = LspIgnoreCommand.main(arrayOf("--config", configPath.toString()))

    assertEquals(0, exitCode)
    assertFalse(invoked, "Expected no git invocation for a bundle with no candidate files")
  }

  // ── one bundle failing doesn't abort the rest ───────────────────────────

  @Test
  fun `git failure for one bundle does not abort processing remaining bundles`() {
    val baseDir = Files.createTempDirectory("lsp-ignore-partial-failure")
    val failingBundle = writeBundleFiles(baseDir.resolve("org.example.fails"), project = true, classpath = false, prefs = emptyList())
    val okBundle = writeBundleFiles(baseDir.resolve("org.example.ok"), project = true, classpath = false, prefs = emptyList())
    val configPath = writeConfig(baseDir, listOf(failingBundle, okBundle))

    val invocations = mutableListOf<Invocation>()
    LspIgnoreCommand.gitUpdateIndexRunner = { moduleDir, flag, files ->
      invocations += Invocation(moduleDir, flag, files)
      moduleDir != failingBundle
    }

    val exitCode = LspIgnoreCommand.main(arrayOf("--config", configPath.toString()))

    assertEquals(0, exitCode)
    assertEquals(2, invocations.size, "Expected both bundles to be processed despite one failing")
    assertEquals(setOf(failingBundle, okBundle), invocations.map { it.moduleDir }.toSet())
  }

  // ── no config found ──────────────────────────────────────────────────────

  @Test
  fun `returns 1 without touching git when no config is discovered`() {
    val baseDir = Files.createTempDirectory("lsp-ignore-no-config")

    var invoked = false
    LspIgnoreCommand.gitUpdateIndexRunner = { _, _, _ -> invoked = true; true }

    val exitCode = LspIgnoreCommand.main(arrayOf("--issue-dir", baseDir.toString()))

    assertEquals(1, exitCode)
    assertFalse(invoked, "Expected no git invocation when config resolution fails")
  }

  @Test
  fun `returns 1 without touching git when explicit config path is missing`() {
    val baseDir = Files.createTempDirectory("lsp-ignore-missing-explicit-config")
    val missingConfig = baseDir.resolve("nope.yaml")

    var invoked = false
    LspIgnoreCommand.gitUpdateIndexRunner = { _, _, _ -> invoked = true; true }

    val exitCode = LspIgnoreCommand.main(arrayOf("--config", missingConfig.toString()))

    assertEquals(1, exitCode)
    assertFalse(invoked, "Expected no git invocation when the explicit --config path doesn't exist")
  }

  // ── candidateFiles unit coverage ─────────────────────────────────────────

  @Test
  fun `candidateFiles finds all settings prefs files`() {
    val baseDir = Files.createTempDirectory("lsp-ignore-candidate-files")
    val bundle = writeBundleFiles(
      baseDir.resolve("org.example.multi"),
      project = true,
      classpath = true,
      prefs = listOf("org.eclipse.jdt.core.prefs", "org.eclipse.jdt.ui.prefs")
    )

    val files = LspIgnoreCommand.candidateFiles(bundle)
    assertEquals(
      setOf(".project", ".classpath", ".settings/org.eclipse.jdt.core.prefs", ".settings/org.eclipse.jdt.ui.prefs"),
      files.map { bundle.relativize(it).toString() }.toSet()
    )
  }

  // ── fixtures ─────────────────────────────────────────────────────────────

  private fun writeBundleFiles(bundleDir: Path, project: Boolean, classpath: Boolean, prefs: List<String>): Path {
    Files.createDirectories(bundleDir)
    if (project) Files.writeString(bundleDir.resolve(".project"), "<projectDescription/>")
    if (classpath) Files.writeString(bundleDir.resolve(".classpath"), "<classpath/>")
    if (prefs.isNotEmpty()) {
      val settingsDir = bundleDir.resolve(".settings")
      Files.createDirectories(settingsDir)
      prefs.forEach { name -> Files.writeString(settingsDir.resolve(name), "eclipse.preferences.version=1\n") }
    }
    return bundleDir.toAbsolutePath().normalize()
  }

  private fun writeConfig(baseDir: Path, bundleDirs: List<Path>): Path {
    val configPath = baseDir.resolve("pde.yaml")
    val bundlesYaml = bundleDirs.joinToString("\n") { dir -> "  - path: ${dir.toAbsolutePath()}" }
    Files.writeString(
      configPath,
      listOf(
        "bundles:",
        bundlesYaml
      ).joinToString("\n")
    )
    return configPath
  }
}
