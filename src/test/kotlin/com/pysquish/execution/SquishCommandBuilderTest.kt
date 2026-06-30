package com.pysquish.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SquishCommandBuilderTest {

    @Test
    fun `splits plain arguments`() {
        assertEquals(
            listOf("--reportgen", "stdout", "--tags", "smoke"),
            SquishCommandBuilder.parseArgs("--reportgen stdout --tags smoke"),
        )
    }

    @Test
    fun `honors quoted arguments with spaces`() {
        assertEquals(
            listOf("--config", "a b c", "--x", "y"),
            SquishCommandBuilder.parseArgs("--config \"a b c\" --x 'y'"),
        )
    }

    @Test
    fun `blank input yields no arguments`() {
        assertEquals(emptyList<String>(), SquishCommandBuilder.parseArgs("   "))
        assertEquals(emptyList<String>(), SquishCommandBuilder.parseArgs(null))
    }
}
