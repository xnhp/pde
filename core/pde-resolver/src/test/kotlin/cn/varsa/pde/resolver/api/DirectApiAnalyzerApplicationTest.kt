package cn.varsa.pde.resolver.api

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectApiAnalyzerApplicationTest {
  @get:Rule
  val temp = TemporaryFolder()

  @Test
  fun `parses input path from separate argument`() {
    val input = temp.newFile("input.json").toPath()

    val parsed = DirectApiAnalyzerApplication.parseInputPath(arrayOf("--input", input.toString()))

    assertEquals(input, parsed)
  }

  @Test
  fun `parses input path from equals argument`() {
    val input = temp.newFile("input.json").toPath()

    val parsed = DirectApiAnalyzerApplication.parseInputPath(arrayOf("--input=$input"))

    assertEquals(input, parsed)
  }

  @Test
  fun `round trips direct analyzer input manifest`() {
    val root = temp.root.toPath()
    val input = DirectApiAnalyzerInput(
      currentBundle = AnalyzerBundleArtifact(
        bundleSymbolicName = "org.example.current",
        version = "1.1.0",
        path = root.resolve("current.jar"),
        synthetic = true
      ),
      dependencyArtifacts = listOf(
        AnalyzerBundleArtifact(
          bundleSymbolicName = "org.example.dep",
          version = "1.0.0",
          path = root.resolve("dep.jar")
        )
      ),
      baselineArtifacts = listOf(
        AnalyzerBundleArtifact(
          bundleSymbolicName = "org.example.current",
          version = "1.0.0",
          path = root.resolve("baseline.jar")
        )
      ),
      apiFilterFile = root.resolve(".settings/.api_filters"),
      preferences = mapOf("api.severity" to "error"),
      outputReportPath = root.resolve("report.json")
    )
    val file = root.resolve("input.json")
    java.nio.file.Files.writeString(file, DirectApiAnalyzerInputJson.write(input))

    val parsed = DirectApiAnalyzerInputJson.read(file)

    assertEquals(input, parsed)
  }
}
