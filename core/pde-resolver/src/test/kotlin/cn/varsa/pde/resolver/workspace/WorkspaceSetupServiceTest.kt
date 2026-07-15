package cn.varsa.pde.resolver.workspace

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WorkspaceSetupServiceTest {
  @Test
  fun `project name is deterministic`() {
    val name1 = WorkspaceSetupService.projectName("com.example.bundle")
    val name2 = WorkspaceSetupService.projectName("com.example.bundle")
    assertEquals(name1, name2)
  }

  @Test
  fun `project name is the bsn itself`() {
    assertEquals("com.example.bundle", WorkspaceSetupService.projectName("com.example.bundle"))
  }

  @Test
  fun `different bsns produce different names`() {
    val name1 = WorkspaceSetupService.projectName("a")
    val name2 = WorkspaceSetupService.projectName("b")
    assertNotEquals(name1, name2)
  }
}
