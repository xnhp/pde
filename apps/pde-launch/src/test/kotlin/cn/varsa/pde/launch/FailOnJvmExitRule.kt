package cn.varsa.pde.launch

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns a JVM exit during a test into a hard test-worker failure.
 *
 * `exitProcess`/`System.exit` from code under test ends the Gradle test worker. With exit code 0
 * Gradle treats that as a clean finish: the running test is reported as skipped, tests that have
 * not started yet are silently dropped, and the build stays green. This rule records which test is
 * running; if the JVM begins shutting down while a test is still in progress, it prints the
 * offending test and halts with a non-zero code so Gradle fails the build instead.
 */
class FailOnJvmExitRule : TestWatcher() {
  override fun starting(description: Description) {
    running.set(description.displayName)
  }

  override fun finished(description: Description) {
    running.set(null)
  }

  companion object {
    private val running = AtomicReference<String?>(null)

    init {
      Runtime.getRuntime().addShutdownHook(Thread {
        val test = running.get() ?: return@Thread
        System.err.println("JVM exit requested while test '$test' was running (exitProcess/System.exit in code under test)")
        System.err.flush()
        Runtime.getRuntime().halt(1)
      })
    }
  }
}
