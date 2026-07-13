package cn.varsa.pde.resolver.workspace

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WorkspaceSetupServiceTest {
  @Test
  fun `invisible project name is deterministic`() {
    val name1 = WorkspaceSetupService.invisibleProjectName("com.example.bundle", "/path/to/bundle")
    val name2 = WorkspaceSetupService.invisibleProjectName("com.example.bundle", "/path/to/bundle")
    assertEquals(name1, name2)
  }

  @Test
  fun `different paths produce different names`() {
    val name1 = WorkspaceSetupService.invisibleProjectName("com.example.bundle", "/path/to/a")
    val name2 = WorkspaceSetupService.invisibleProjectName("com.example.bundle", "/path/to/b")
    assertNotEquals(name1, name2)
  }

  @Test
  fun `different bsns produce different names`() {
    val name1 = WorkspaceSetupService.invisibleProjectName("a", "/path/to/a-bundle")
    val name2 = WorkspaceSetupService.invisibleProjectName("b", "/path/to/b-bundle")
    assertNotEquals(name1, name2)
  }
}
