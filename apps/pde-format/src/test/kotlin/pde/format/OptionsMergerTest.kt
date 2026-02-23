package pde.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OptionsMergerTest {
    @Test
    fun `profile options override defaults`() {
        val defaults = mapOf("a" to "1", "b" to "2")
        val overrides = mapOf("b" to "3", "c" to "4")

        val merged = OptionsMerger.merge(defaults, overrides)

        assertEquals(mapOf("a" to "1", "b" to "3", "c" to "4"), merged)
    }
}
