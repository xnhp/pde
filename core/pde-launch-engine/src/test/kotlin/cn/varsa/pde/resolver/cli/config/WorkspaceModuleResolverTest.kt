package cn.varsa.pde.resolver.cli.config

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceModuleResolverTest {
  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun preservesClassRootsWhenResolvingDefinitions() {
    val repo = tmp.newFolder("repo").toPath()
    val implicit = repo.resolve("bundle.implicit").toString()
    val empty = repo.resolve("bundle.empty").toString()
    val explicit = repo.resolve("bundle.explicit").toString()

    val context = LaunchConfigContext(
      file = repo.resolve("pde.yaml"),
      baseDir = repo,
      workingDir = repo,
      config = LaunchConfig(
        bundles = listOf(
          WorkspaceBundleConfig(path = implicit),
          WorkspaceBundleConfig(path = empty, classRoots = emptyList()),
          WorkspaceBundleConfig(path = explicit, classRoots = listOf("bin/eclipse"))
        )
      )
    )

    val definitions = WorkspaceModuleResolver.resolveDefinitions(context)

    assertNull(definitions.first { it.moduleDir.endsWith("bundle.implicit") }.classRoots)
    assertEquals(emptyList(), definitions.first { it.moduleDir.endsWith("bundle.empty") }.classRoots)
    assertEquals(listOf("bin/eclipse"), definitions.first { it.moduleDir.endsWith("bundle.explicit") }.classRoots)
  }

  @Test
  fun resolvesRelativeBundlePathAgainstBaseDirNotWorkingDir() {
    val configDir = tmp.newFolder("config-dir").toPath()
    val workingDir = tmp.newFolder("working-dir").toPath()

    val context = LaunchConfigContext(
      file = configDir.resolve("pde.yaml"),
      baseDir = configDir,
      workingDir = workingDir,
      config = LaunchConfig(bundles = listOf(WorkspaceBundleConfig(path = "my.bundle")))
    )

    val moduleDir = WorkspaceModuleResolver.resolveDefinitions(context).single().moduleDir

    assertEquals(configDir.resolve("my.bundle").toAbsolutePath().normalize(), moduleDir)
  }
}
