package cn.varsa.pde.remoterunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteTestProcessorTest {
  @Test
  fun `parses passing and failing tests`() {
    val stream = """
      %TESTC  2 1
      %TSTTREE@1,testPass(com.example.SampleTest),false,1,false,-1,PassHelper::run,,
      %TESTS  @1,testPass(com.example.SampleTest)
      %TESTE  @1,testPass(com.example.SampleTest)
      %TSTTREE@2,testFail(com.example.SampleTest),false,1,false,-1,FailHelper::run,,
      %TESTS  @2,testFail(com.example.SampleTest)
      %FAILED @2,testFail(com.example.SampleTest)
      %TRACES 
      java.lang.AssertionError: boom
      at line
      %TRACEE 
      %TESTE  @2,testFail(com.example.SampleTest)
      %RUNTIME00012
    """.trimIndent()

    val recorder = RecordingRemoteTestListener()
    val processor = RemoteTestProcessor(CompositeRemoteTestListener(listOf(recorder)))
    val summary = processor.process(stream.reader().buffered())

    assertEquals(2, summary.finishedTests)
    assertEquals(1, summary.failures)
    val passed = recorder.results.first()
    assertEquals("PassHelper::run", passed.descriptor.displayName)
    val failed = recorder.results.last()
    assertEquals(RemoteTestStatus.FAILED, failed.status)
    assertTrue(failed.trace!!.contains("AssertionError"))
  }
}
