package cn.varsa.pde.launch

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IjInitTest {
  @Test
  fun `copyIjTemplate creates expected files`() {
    val targetDir = Files.createTempDirectory("ij-init-template")
    IjInit.copyIjTemplate(targetDir)

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
    IjInit.copyIjTemplate(targetDir)

    IjInit.updateEclipseTargetLocation(targetDir, "/opt/knime/target/Profile.profile")

    val updated = Files.readString(targetDir.resolve(".idea/eclipse-partial.xml"))
    assertTrue(updated.contains("location=\"/opt/knime/target/Profile.profile\""))
  }

  @Test
  fun `updateEclipseFormatterConfig writes formatter settings`() {
    val targetDir = Files.createTempDirectory("ij-init-formatter")
    IjInit.copyIjTemplate(targetDir)

    IjInit.updateEclipseFormatterConfig(targetDir, "/opt/knime/org.eclipse.jdt.core.prefs")

    val formatter = Files.readString(targetDir.resolve(".idea/eclipseCodeFormatter.xml"))
    assertTrue(formatter.contains("pathToConfigFileJava"))
    assertTrue(formatter.contains("/opt/knime/org.eclipse.jdt.core.prefs"))
    assertTrue(formatter.contains("EclipseCodeFormatterProjectSettings"))
  }

  @Test
  fun `normalizeProfilePath trims trailing separator`() {
    assertEquals("/opt/knime/target/Profile.profile", IjInit.normalizeProfilePath("/opt/knime/target/Profile.profile/"))
  }

  @Test
  fun `resolveProfilePath resolves relative paths`() {
    val baseDir = Files.createTempDirectory("ij-init-profile")
    val relative = "target/p2/org.eclipse.equinox.p2.engine/profileRegistry/Profile.profile"
    val expected = baseDir.resolve(relative).normalize().toString()
    assertEquals(expected, IjInit.resolveProfilePath(baseDir, relative))
  }

  @Test
  fun `initIjProjectFromConfig defaults profile path to issue dir`() {
    val issueDir = Files.createTempDirectory("ij-init-issue")
    val configDir = issueDir.resolve("config")
    Files.createDirectories(configDir)
    val repoDir = configDir.resolve("knime-core").resolve("org.knime.core")
    Files.createDirectories(repoDir.resolve("src"))
    val configPath = configDir.resolve("config.yaml")
    Files.writeString(
      configPath,
      """
        target: {}
        bundlesPerRepo:
          - repo: knime-core
            bundles:
              - org.knime.core
      """.trimIndent()
    )
    val profileFile = issueDir
      .resolve("target")
      .resolve("p2")
      .resolve("org.eclipse.equinox.p2.engine")
      .resolve("profileRegistry")
      .resolve("profile.profile")
      .resolve("Profile.profile")
    Files.createDirectories(profileFile.parent)
    Files.writeString(profileFile, "")

    IjInit.initIjProjectFromConfig(configPath, issueDir)

    val eclipsePartial = Files.readString(configDir.resolve("ij-project/.idea/eclipse-partial.xml"))
    val expected = profileFile.toAbsolutePath().normalize().toString()
    assertTrue(eclipsePartial.contains("location=\"${expected}\""))
  }

  @Test
  fun `initIjProjectFromConfig writes module files`() {
    val baseDir = Files.createTempDirectory("ij-init-config")
    val configPath = baseDir.resolve("config.yaml")
    val repoDir = baseDir.resolve("knime-core").resolve("org.knime.core")
    Files.createDirectories(repoDir.resolve("src"))
    Files.writeString(repoDir.resolve("package.json"), "{}")
    Files.writeString(
      configPath,
      """
        bundlesPerRepo:
          - repo: knime-core
            bundles:
              - org.knime.core
      """.trimIndent()
    )

    IjInit.initIjProjectFromConfig(configPath)

    val moduleFile = baseDir.resolve("ij-project/ij-module-files/org.knime.core.iml")
    assertTrue(Files.exists(moduleFile))
    val moduleContents = Files.readString(moduleFile)
    val expectedRoot = repoDir.toAbsolutePath().normalize().toUri().toString().removeSuffix("/")
    assertTrue(moduleContents.contains("content url=\"${expectedRoot}\""))
    assertTrue(moduleContents.contains("sourceFolder url=\"${expectedRoot}/src\""))
    assertTrue(moduleContents.contains("excludeFolder url=\"${expectedRoot}/node_modules\""))
    assertTrue(!moduleContents.contains("//src"))
    assertTrue(!moduleContents.contains("//node_modules"))
    val modulesXml = Files.readString(baseDir.resolve("ij-project/.idea/modules.xml"))
    assertTrue(modulesXml.contains("org.knime.core.iml"))
  }

  @Test
  fun `findConfigPath finds config in parent directory`() {
    val baseDir = Files.createTempDirectory("ij-init-find")
    val configPath = baseDir.resolve("config.yaml")
    Files.writeString(configPath, "bundlesPerRepo: []\n")

    val nestedDir = baseDir.resolve("nested").resolve("child")
    Files.createDirectories(nestedDir)

    val resolved = IjInit.findConfigPath(nestedDir)
    assertNotNull(resolved)
    assertEquals(configPath.normalize().toString(), resolved.normalize().toString())
  }
}
