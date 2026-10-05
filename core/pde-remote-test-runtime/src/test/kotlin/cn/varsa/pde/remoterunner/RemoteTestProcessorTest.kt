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

  @Test
  fun `reports aborted and ignored tests as skipped`() {
    val stream = """
      %TESTC  2 1
      %TESTS  @1,testAborted(com.example.SampleTest)
      %FAILED @1,@AssumptionFailure: testAborted(com.example.SampleTest)
      %TRACES 
      org.opentest4j.TestAbortedException: Assumption failed
      at line
      %TRACEE 
      %TESTE  @1,testAborted(com.example.SampleTest)
      %TESTS  @2,@Ignore: testIgnored(com.example.SampleTest)
      %TESTE  @2,@Ignore: testIgnored(com.example.SampleTest)
      %RUNTIME00012
    """.trimIndent()

    val recorder = RecordingRemoteTestListener()
    val processor = RemoteTestProcessor(CompositeRemoteTestListener(listOf(recorder)))
    val summary = processor.process(stream.reader().buffered())

    assertEquals(2, summary.finishedTests)
    assertEquals(0, summary.failures)
    assertEquals(2, summary.skipped)
    assertEquals(listOf(RemoteTestStatus.SKIPPED, RemoteTestStatus.SKIPPED), recorder.results.map { it.status })
    assertEquals("com.example.SampleTest.testAborted", recorder.results.first().descriptor.printableName)
  }
}
