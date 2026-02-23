package pde.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ProfileParserTest {
    @Test
    fun `prefs file is treated as properties`() {
        val tempDir = Files.createTempDirectory("pde-format-test")
        val prefs = tempDir.resolve("org.eclipse.jdt.core.prefs")
        Files.writeString(prefs, "org.eclipse.jdt.core.formatter.tabulation.char=space")

        val options = ProfileParser.loadOptions(prefs)

        assertEquals("space", options["org.eclipse.jdt.core.formatter.tabulation.char"])
    }
}
