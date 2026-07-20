package cn.varsa.pde.launch

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitConfigTest {

  private val originalGitDiffRunner = InitConfig.gitDiffRunner

  @AfterTest
  fun restoreGitDiffRunner() {
    InitConfig.gitDiffRunner = originalGitDiffRunner
  }

  // ── findBundleRoot ────────────────────────────────────────────────────────

  @Test
  fun `findBundleRoot finds manifest walking up from source file`() {
    val worktree = Files.createTempDirectory("init-config-wt")
    val bundle = worktree.resolve("org.example.bundle")
    Files.createDirectories(bundle.resolve("META-INF"))
    Files.createFile(bundle.resolve("META-INF/MANIFEST.MF"))
    val src = bundle.resolve("src/Main.java")
    Files.createDirectories(src.parent)
    Files.createFile(src)

    val result = InitConfig.findBundleRoot(worktree, src)
    assertEquals(bundle.toAbsolutePath().normalize(), result)
  }

  @Test
  fun `findBundleRoot returns null when no manifest exists`() {
    val worktree = Files.createTempDirectory("init-config-no-manifest")
    val src = worktree.resolve("src/Main.java")
    Files.createDirectories(src.parent)
    Files.createFile(src)

    assertNull(InitConfig.findBundleRoot(worktree, src))
  }

  @Test
  fun `findBundleRoot does not escape worktree root`() {
    val parent = Files.createTempDirectory("init-config-escape-parent")
    // manifest is in the parent, NOT inside the worktree
    Files.createDirectories(parent.resolve("META-INF"))
    Files.createFile(parent.resolve("META-INF/MANIFEST.MF"))
    val worktree = Files.createDirectories(parent.resolve("worktree"))
    val src = worktree.resolve("src/Foo.java")
    Files.createDirectories(src.parent)
    Files.createFile(src)

    // The manifest in parent must not be found because it is outside worktree
    assertNull(InitConfig.findBundleRoot(worktree, src))
  }

  // ── discoverIncludes ──────────────────────────────────────────────────────

  @Test
  fun `discoverIncludes finds target yaml and launches yaml in parent`() {
    val parent = Files.createTempDirectory("init-config-discover-parent")
    val issueDir = Files.createDirectories(parent.resolve("issue-WS-123"))

    Files.writeString(parent.resolve("target.yaml"), "# target\n")
    Files.writeString(parent.resolve("launches.yaml"), "# launches\n")

    val includes = InitConfig.discoverIncludes(issueDir)
    assertEquals(listOf("../target.yaml", "../launches.yaml"), includes)
  }

  @Test
  fun `discoverIncludes returns empty when no shared config exists`() {
    val parent = Files.createTempDirectory("init-config-discover-empty")
    val issueDir = Files.createDirectories(parent.resolve("issue-WS-456"))

    assertTrue(InitConfig.discoverIncludes(issueDir).isEmpty())
  }

  @Test
  fun `discoverIncludes includes only present files`() {
    val parent = Files.createTempDirectory("init-config-discover-partial")
    val issueDir = Files.createDirectories(parent.resolve("issue-WS-789"))

    Files.writeString(parent.resolve("target.yaml"), "# target\n")
    // launches.yaml intentionally absent

    val includes = InitConfig.discoverIncludes(issueDir)
    assertEquals(listOf("../target.yaml"), includes)
  }

  // ── main (with injected runner) ───────────────────────────────────────────

  @Test
  fun `main dry-run outputs pde yaml with detected bundles and includes`() {
    val parent = Files.createTempDirectory("init-config-main-parent")
    val issueDir = Files.createDirectories(parent.resolve("issue-WS-001"))

    // Set up a worktree subdir identified by a .git file
    val worktree = Files.createDirectories(issueDir.resolve("my-repo"))
    Files.writeString(worktree.resolve(".git"), "gitdir: ../.git/worktrees/my-repo\n")

    // Bundle with manifest inside the worktree
    val bundle = worktree.resolve("org.example.bundle")
    Files.createDirectories(bundle.resolve("META-INF"))
    Files.createFile(bundle.resolve("META-INF/MANIFEST.MF"))
    val srcFile = bundle.resolve("src/Main.java")
    Files.createDirectories(srcFile.parent)
    Files.createFile(srcFile)

    // Shared config in parent directory
    Files.writeString(parent.resolve("target.yaml"), "# target\n")

    // Inject fake runner: returns one changed file relative to worktree root
    InitConfig.gitDiffRunner = { _, _ ->
      listOf(Paths.get("org.example.bundle/src/Main.java"))
    }

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    val exitCode: Int
    try {
      exitCode = InitConfig.main(arrayOf("--dry-run", issueDir.toString()))
    } finally {
      System.setOut(savedOut)
    }

    assertEquals(0, exitCode)
    val output = out.toString()
    assertTrue(output.contains("includes:"), "Expected includes section; got:\n$output")
    assertTrue(output.contains("../target.yaml"), "Expected target.yaml include; got:\n$output")
    assertTrue(output.contains("bundles:"), "Expected bundles section; got:\n$output")
    assertTrue(output.contains("my-repo/org.example.bundle"), "Expected bundle path; got:\n$output")
    // pde.yaml must NOT be created on disk (dry-run)
    assertFalse(Files.exists(issueDir.resolve("pde.yaml")))
  }

  @Test
  fun `main writes pde yaml to disk when not dry-run`() {
    val issueDir = Files.createTempDirectory("init-config-write")

    val worktree = Files.createDirectories(issueDir.resolve("repo-a"))
    Files.writeString(worktree.resolve(".git"), "gitdir: ../.git/worktrees/repo-a\n")

    val bundle = worktree.resolve("com.example.core")
    Files.createDirectories(bundle.resolve("META-INF"))
    Files.createFile(bundle.resolve("META-INF/MANIFEST.MF"))

    InitConfig.gitDiffRunner = { _, _ ->
      listOf(Paths.get("com.example.core/src/Core.java"))
    }

    val exitCode = InitConfig.main(arrayOf(issueDir.toString()))
    assertEquals(0, exitCode)

    val written = issueDir.resolve("pde.yaml")
    assertTrue(Files.exists(written), "pde.yaml should have been written to disk")
    val content = Files.readString(written)
    assertTrue(content.contains("repo-a/com.example.core"), "Written yaml missing bundle path; got:\n$content")
  }

  @Test
  fun `main returns exit 3 when pde yaml already exists`() {
    val issueDir = Files.createTempDirectory("init-config-existing")
    val worktree = Files.createDirectories(issueDir.resolve("repo"))
    Files.writeString(worktree.resolve(".git"), "gitdir: ../.git/worktrees/repo\n")
    // Pre-existing pde.yaml
    Files.writeString(issueDir.resolve("pde.yaml"), "bundles: []\n")

    val exitCode = InitConfig.main(arrayOf(issueDir.toString()))
    assertEquals(3, exitCode)
  }

  @Test
  fun `main returns exit 2 when no worktrees found`() {
    val issueDir = Files.createTempDirectory("init-config-no-wt")
    // No subdirs with .git
    val exitCode = InitConfig.main(arrayOf(issueDir.toString()))
    assertEquals(2, exitCode)
  }

  @Test
  fun `main emits empty bundles list when runner returns no matching bundles`() {
    val issueDir = Files.createTempDirectory("init-config-no-bundles")
    val worktree = Files.createDirectories(issueDir.resolve("repo"))
    Files.writeString(worktree.resolve(".git"), "gitdir: ../.git/worktrees/repo\n")

    // Runner returns a file that is NOT inside an OSGi bundle (no MANIFEST.MF up the tree)
    InitConfig.gitDiffRunner = { _, _ ->
      listOf(Paths.get("README.md"))
    }

    val out = ByteArrayOutputStream()
    val savedOut = System.out
    System.setOut(PrintStream(out))
    val exitCode: Int
    try {
      exitCode = InitConfig.main(arrayOf("--dry-run", issueDir.toString()))
    } finally {
      System.setOut(savedOut)
    }

    assertEquals(0, exitCode)
    val output = out.toString()
    assertTrue(output.contains("bundles: []"), "Expected empty bundles list; got:\n$output")
  }
}
