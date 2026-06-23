package cn.varsa.pde.launch

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IjInitTest {
  @Test
  fun `copyIjTemplate creates expected files`() {
    val targetDir = Files.createTempDirectory("ij-init-template")
    IjInit.copyIjTemplate(targetDir, "ij-project")

    assertTrue(Files.isDirectory(targetDir.resolve(".idea")))
    assertTrue(Files.isDirectory(targetDir.resolve("src")))
    assertTrue(Files.isRegularFile(targetDir.resolve(".gitignore")))

    val gitignore = Files.readString(targetDir.resolve(".gitignore"))
    assertTrue(gitignore.contains("### IntelliJ IDEA ###"))

    val workspace = Files.readString(targetDir.resolve(".idea/workspace.xml"))
    assertTrue(workspace.contains("ProjectViewState"))

    val module = Files.readString(targetDir.resolve("ij-project.iml"))
    assertTrue(module.contains("excludeFolder"))
  }

  @Test
  fun `updateEclipseTargetLocation replaces profile location`() {
    val targetDir = Files.createTempDirectory("ij-init-target")
    IjInit.copyIjTemplate(targetDir, "ij-project")

    IjInit.updateEclipseTargetLocation(targetDir, "/opt/knime/target/profile.profile")

    val updated = Files.readString(targetDir.resolve(".idea/eclipse-partial.xml"))
    assertTrue(updated.contains("location=\"/opt/knime/target/profile.profile\""))
  }

  @Test
  fun `updateEclipseFormatterConfig writes formatter settings`() {
    val targetDir = Files.createTempDirectory("ij-init-formatter")
    IjInit.copyIjTemplate(targetDir, "ij-project")

    IjInit.updateEclipseFormatterConfig(targetDir, "/opt/knime/org.eclipse.jdt.core.prefs")

    val formatter = Files.readString(targetDir.resolve(".idea/eclipseCodeFormatter.xml"))
    assertTrue(formatter.contains("pathToConfigFileJava"))
    assertTrue(formatter.contains("/opt/knime/org.eclipse.jdt.core.prefs"))
    assertTrue(formatter.contains("EclipseCodeFormatterProjectSettings"))
  }

  @Test
  fun `normalizeProfilePath trims trailing separator`() {
    assertEquals("/opt/knime/target/profile.profile", IjInit.normalizeProfilePath("/opt/knime/target/profile.profile/"))
  }

  @Test
  fun `resolveProfilePath resolves relative paths`() {
    val baseDir = Files.createTempDirectory("ij-init-profile")
    val relative = "target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.profile"
    val expected = baseDir.resolve(relative).normalize().toString()
    assertEquals(expected, IjInit.resolveProfilePath(baseDir, relative))
  }

  @Test
  fun `initIjProjectFromConfig defaults profile path to issue dir`() {
    val issueDir = Files.createTempDirectory("ij-init-issue")
    Files.writeString(issueDir.resolve("issue.yaml"), "id: EX-9\n")
    val configDir = issueDir.resolve("config")
    Files.createDirectories(configDir)
    val repoDir = configDir.resolve("knime-core").resolve("org.knime.core")
    Files.createDirectories(repoDir.resolve("src"))
    val configPath = configDir.resolve("pde.yaml")
    Files.writeString(
      configPath,
        """
        target: {}
        bundles:
          - path: knime-core/org.knime.core
      """.trimIndent()
    )
    val profileDir = configDir
      .resolve("target")
      .resolve("p2")
      .resolve("org.eclipse.equinox.p2.engine")
      .resolve("profileRegistry")
      .resolve("profile.profile")
    Files.createDirectories(profileDir)
    val issueProfileDir = issueDir
      .resolve("target")
      .resolve("p2")
      .resolve("org.eclipse.equinox.p2.engine")
      .resolve("profileRegistry")
      .resolve("profile.profile")
    Files.createDirectories(issueProfileDir)

    IjInit.initIjProjectFromConfig(configPath, issueDir)

    val eclipsePartial = Files.readString(issueDir.resolve("ij-project/.idea/eclipse-partial.xml"))
    val expected = "\$PROJECT_DIR\$/../target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.profile"
    assertTrue(eclipsePartial.contains("location=\"${expected}\""))
  }

  @Test
  fun `initIjProjectFromConfig writes module files`() {
    val baseDir = Files.createTempDirectory("ij-init-config")
    Files.writeString(baseDir.resolve("issue.yaml"), "id: EX-9\n")
    val configPath = baseDir.resolve("pde.yaml")
    val repoDir = baseDir.resolve("knime-core").resolve("org.knime.core")
    Files.createDirectories(repoDir.resolve("src"))
    Files.writeString(repoDir.resolve("package.json"), "{}")
    Files.writeString(
      configPath,
      """
        bundles:
          - path: knime-core/org.knime.core
      """.trimIndent()
    )

    IjInit.initIjProjectFromConfig(configPath)

    val moduleFile = baseDir.resolve("ij-project/ij-module-files/org.knime.core.iml")
    assertTrue(Files.exists(moduleFile))
    val moduleContents = Files.readString(moduleFile)
    val expectedRoot = "file://\$MODULE_DIR\$/../../knime-core/org.knime.core"
    assertTrue(moduleContents.contains("content url=\"${expectedRoot}\""))
    assertTrue(moduleContents.contains("sourceFolder url=\"${expectedRoot}/src\""))
    assertTrue(moduleContents.contains("excludeFolder url=\"${expectedRoot}/node_modules\""))
    assertTrue(moduleContents.contains("type=\"cn.varsa.idea.pde.tools.plugin\""))
    assertTrue(moduleContents.contains("name=\"PDE Tools\""))
    assertTrue(!moduleContents.contains("//src"))
    assertTrue(!moduleContents.contains("//node_modules"))
    val modulesXml = Files.readString(baseDir.resolve("ij-project/.idea/modules.xml"))
    assertTrue(modulesXml.contains("EX-9.iml"))
    assertTrue(modulesXml.contains("org.knime.core.iml"))
  }

  @Test
  fun `initIjProjectFromConfig writes launcher formatter and vcs settings`() {
    val rootDir = Files.createTempDirectory("ij-init-root")
    val issueDir = rootDir.resolve("todo_EX-9-example")
    Files.createDirectories(issueDir)
    Files.writeString(issueDir.resolve("issue.yaml"), "id: EX-9\n")
    Files.writeString(rootDir.resolve("org.eclipse.jdt.core.prefs"), "eclipse.preferences.version=1\n")
    val bundlePoolPlugins = rootDir.resolve("bundle-pool/plugins")
    Files.createDirectories(bundlePoolPlugins)
    Files.writeString(bundlePoolPlugins.resolve("org.eclipse.equinox.launcher_1.0.0.jar"), "")
    val repoDir = issueDir.resolve("repo")
    Files.createDirectories(repoDir.resolve(".git"))
    val bundleDir = repoDir.resolve("org.example.bundle")
    Files.createDirectories(bundleDir.resolve("src/eclipse"))
    Files.createDirectories(bundleDir.resolve("src-deprecated"))
    Files.writeString(bundleDir.resolve("build.properties"), "source.. = src-deprecated/, src/eclipse/\n")
    val configPath = issueDir.resolve("pde.yaml")
    Files.writeString(
      configPath,
      """
        target:
          bundlePool: ../bundle-pool
        bundles:
          - path: repo/org.example.bundle
      """.trimIndent()
    )

    IjInit.initIjProjectFromConfig(configPath, issueDir)

    val projectDir = issueDir.resolve("ij-project")
    val eclipsePartial = Files.readString(projectDir.resolve(".idea/eclipse-partial.xml"))
    assertTrue(eclipsePartial.contains("location=\"\$PROJECT_DIR\$/../target/p2/org.eclipse.equinox.p2.engine/profileRegistry/profile.profile\""))
    assertTrue(eclipsePartial.contains("launcherJar=\"\$PROJECT_DIR\$/../../bundle-pool/plugins/org.eclipse.equinox.launcher_1.0.0.jar\""))

    val formatter = Files.readString(projectDir.resolve(".idea/eclipseCodeFormatter.xml"))
    assertTrue(formatter.contains("pathToConfigFileJava\" value=\"\$PROJECT_DIR\$/../../org.eclipse.jdt.core.prefs\""))
    assertTrue(!formatter.contains("\\\""))

    val vcs = Files.readString(projectDir.resolve(".idea/vcs.xml"))
    assertTrue(!vcs.contains("directory=\"\$PROJECT_DIR\$\""))
    assertTrue(vcs.contains("directory=\"\$PROJECT_DIR\$/../repo\""))

    val module = Files.readString(projectDir.resolve("ij-module-files/org.example.bundle.iml"))
    assertTrue(module.contains("sourceFolder url=\"file://\$MODULE_DIR\$/../../repo/org.example.bundle/src-deprecated\""))
    assertTrue(module.contains("sourceFolder url=\"file://\$MODULE_DIR\$/../../repo/org.example.bundle/src/eclipse\""))
  }

  @Test
  fun `findConfigPath finds config in parent directory`() {
    val baseDir = Files.createTempDirectory("ij-init-find")
    val configPath = baseDir.resolve("pde.yaml")
    Files.writeString(configPath, "bundles: []\n")

    val nestedDir = baseDir.resolve("nested").resolve("child")
    Files.createDirectories(nestedDir)

    val resolved = IjInit.findConfigPath(nestedDir)
    assertNotNull(resolved)
    assertEquals(configPath.normalize().toString(), resolved.normalize().toString())
  }
}
