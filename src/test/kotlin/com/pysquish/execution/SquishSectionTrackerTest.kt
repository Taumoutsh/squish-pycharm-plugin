package com.pysquish.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SquishSectionTrackerTest {

    @Test
    fun `no prefix before any marker`() {
        val t = SquishSectionTracker()
        assertNull(t.prefixFor("10:00:00 LOG hello"))
    }

    @Test
    fun `phase and step come from the Start Section marker and persist`() {
        val t = SquishSectionTracker()
        assertEquals("P1-S2", t.prefixFor("10:00:00 LOG Start Section: Phase 1, Step 2"))
        // Lines without a marker keep the latest phase/step.
        assertEquals("P1-S2", t.prefixFor("10:00:01 LOG doing something"))
        assertEquals("P1-S2", t.prefixFor("10:00:02 PASS ok"))
        // A new marker updates both values (any numbers, no depth logic).
        assertEquals("P2-S1", t.prefixFor("10:00:03 LOG Start Section: Phase 2, Step 1"))
        assertEquals("P2-S1", t.prefixFor("10:00:04 LOG still in phase 2 step 1"))
        assertEquals("P3-S5", t.prefixFor("10:00:05 LOG Start Section: Phase 3, Step 5"))
    }

    @Test
    fun `tag is inserted between timestamp and log type`() {
        assertEquals(
            "10:00:02 P1-S1 LOG message",
            SquishConsolePrinter.insertSectionTag("10:00:02 LOG message", "P1-S1"),
        )
    }

    @Test
    fun `tag is prepended when there is no timestamp`() {
        assertEquals(
            "P1 LOG message",
            SquishConsolePrinter.insertSectionTag("LOG message", "P1"),
        )
    }

    @Test
    fun `no tag leaves the line unchanged`() {
        assertEquals(
            "10:00:02 LOG message",
            SquishConsolePrinter.insertSectionTag("10:00:02 LOG message", null),
        )
    }
}
