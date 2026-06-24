package cn.varsa.pde.resolver.workspace

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceBundleClasspathExportsTest {

  @Test
  fun `reads colon-separated add-exports tokens from JRE_CONTAINER`() {
    val dir = Files.createTempDirectory("ws-cp-exports")
    writeManifest(dir)
    dir.resolve(".classpath").writeText(
      """
        <?xml version="1.0" encoding="UTF-8"?>
        <classpath>
        	<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21">
        		<attributes>
        			<attribute name="module" value="true"/>
        			<attribute name="add-exports" value="java.security.jgss/sun.security.krb5=ALL-UNNAMED:java.security.jgss/sun.security.jgss=ALL-UNNAMED"/>
        			<attribute name="add-opens" value="java.base/java.lang=ALL-UNNAMED"/>
        		</attributes>
        	</classpathentry>
        	<classpathentry kind="con" path="org.eclipse.pde.core.requiredPlugins"/>
        	<classpathentry kind="src" path="src"/>
        	<classpathentry kind="output" path="bin/"/>
        </classpath>
      """.trimIndent()
    )

    val desc = WorkspaceBundleLoader.load(dir)

    assertEquals(
      listOf(
        "java.security.jgss/sun.security.krb5=ALL-UNNAMED",
        "java.security.jgss/sun.security.jgss=ALL-UNNAMED"
      ),
      desc.addExports
    )
    assertEquals(listOf("java.base/java.lang=ALL-UNNAMED"), desc.addOpens)
  }

  @Test
  fun `no classpath yields empty module access lists`() {
    val dir = Files.createTempDirectory("ws-no-cp")
    writeManifest(dir)

    val desc = WorkspaceBundleLoader.load(dir)

    assertTrue(desc.addExports.isEmpty())
    assertTrue(desc.addOpens.isEmpty())
  }

  private fun writeManifest(dir: Path) {
    val metaInf = dir.resolve("META-INF").createDirectories()
    metaInf.resolve("MANIFEST.MF").writeText(
      """
        Manifest-Version: 1.0
        Bundle-ManifestVersion: 2
        Bundle-Name: Test Bundle
        Bundle-SymbolicName: org.example.cp
        Bundle-Version: 1.0.0

      """.trimIndent()
    )
  }
}
