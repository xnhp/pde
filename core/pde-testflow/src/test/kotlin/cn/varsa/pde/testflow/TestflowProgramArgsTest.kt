package cn.varsa.pde.testflow

import kotlin.test.Test
import kotlin.test.assertEquals

class TestflowProgramArgsTest {

  @Test
  fun `maps root and xml result dir to runner args`() {
    val args = buildTestflowProgramArgs(
      TestflowRunOptions(rootDirs = listOf("/flows"), xmlResultDir = "/out")
    )

    assertEquals(listOf("-root", "/flows", "-xmlResultDir", "/out"), args)
  }

  @Test
  fun `emits one -root per directory for multiple roots`() {
    val args = buildTestflowProgramArgs(
      TestflowRunOptions(rootDirs = listOf("/a", "/b"), xmlResultDir = "/out")
    )

    assertEquals(listOf("-root", "/a", "-root", "/b", "-xmlResultDir", "/out"), args)
  }

  @Test
  fun `appends include filter when set`() {
    val args = buildTestflowProgramArgs(
      TestflowRunOptions(rootDirs = listOf("/flows"), xmlResultDir = "/out", include = "/Misc/.*")
    )

    assertEquals(listOf("-root", "/flows", "-xmlResultDir", "/out", "-include", "/Misc/.*"), args)
  }

  @Test
  fun `appends timeout when set`() {
    val args = buildTestflowProgramArgs(
      TestflowRunOptions(rootDirs = listOf("/flows"), xmlResultDir = "/out", timeoutSeconds = 300)
    )

    assertEquals(listOf("-root", "/flows", "-xmlResultDir", "/out", "-timeout", "300"), args)
  }

  @Test
  fun `maps every enabled boolean flag in a stable order`() {
    val args = buildTestflowProgramArgs(
      TestflowRunOptions(
        rootDirs = listOf("/flows"),
        xmlResultDir = "/out",
        loadSaveLoad = true,
        streaming = true,
        views = true,
        dialogs = true,
        checkLogMessages = true,
        ignoreNodeMessages = true,
        deprecated = true,
      )
    )

    assertEquals(
      listOf(
        "-root", "/flows", "-xmlResultDir", "/out",
        "-loadSaveLoad", "-streaming", "-views", "-dialogs",
        "-logMessages", "-ignoreNodeMessages", "-deprecated",
      ),
      args,
    )
  }

  @Test
  fun `omits boolean flags that are disabled`() {
    val args = buildTestflowProgramArgs(
      TestflowRunOptions(
        rootDirs = listOf("/flows"),
        xmlResultDir = "/out",
        loadSaveLoad = true,
        deprecated = true,
      )
    )

    assertEquals(
      listOf("-root", "/flows", "-xmlResultDir", "/out", "-loadSaveLoad", "-deprecated"),
      args,
    )
  }

  @Test
  fun `appends one workflow_variable arg per declaration`() {
    val args = buildTestflowProgramArgs(
      TestflowRunOptions(
        rootDirs = listOf("/flows"),
        xmlResultDir = "/out",
        workflowVariables = listOf("count,5,int", "name,foo,String"),
      )
    )

    assertEquals(
      listOf(
        "-root", "/flows", "-xmlResultDir", "/out",
        "-workflow.variable", "count,5,int",
        "-workflow.variable", "name,foo,String",
      ),
      args,
    )
  }
}
