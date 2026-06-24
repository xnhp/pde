package cn.varsa.pde.testflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestflowRuntimeVmArgsTest {
  @Test
  fun `always runs headless so no GUI windows open`() {
    assertTrue(testflowRuntimeVmArgs("Linux").contains("-Djava.awt.headless=true"))
    assertTrue(testflowRuntimeVmArgs("Windows 11").contains("-Djava.awt.headless=true"))
    assertTrue(testflowRuntimeVmArgs("Mac OS X").contains("-Djava.awt.headless=true"))
  }

  @Test
  fun `macOS also gets the SWT Cocoa-main-thread flag and background-agent flag`() {
    assertEquals(
      listOf("-XstartOnFirstThread", "-Djava.awt.headless=true", "-Dapple.awt.UIElement=true"),
      testflowRuntimeVmArgs("Mac OS X"),
    )
  }

  @Test
  fun `non-macOS does not get the SWT flag`() {
    assertEquals(listOf("-Djava.awt.headless=true"), testflowRuntimeVmArgs("Linux"))
  }
}
