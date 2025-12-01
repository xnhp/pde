package cn.varsa.pde.remoterunner

class RecordingRemoteTestListener : RemoteTestListener {
  private val _results = mutableListOf<RemoteTestResult>()
  private var _summary: RemoteTestSummary? = null

  val results: List<RemoteTestResult>
    get() = _results.toList()

  val summary: RemoteTestSummary?
    get() = _summary

  override fun testFinished(result: RemoteTestResult) {
    _results += result
  }

  override fun runEnded(summary: RemoteTestSummary) {
    _summary = summary
  }

  override fun runStopped(summary: RemoteTestSummary) {
    _summary = summary
  }
}
