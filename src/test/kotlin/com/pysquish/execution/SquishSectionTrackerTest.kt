package com.pysquish.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SquishSectionTrackerTest {

    @Test
    fun `no prefix before any section`() {
        val t = SquishSectionTracker()
        assertNull(t.prefixFor("10:00:00 LOG hello"))
    }

    @Test
    fun `top-level section is a phase, nested is a step`() {
        val t = SquishSectionTracker()
        assertEquals("P1", t.prefixFor("10:00:00 LOG startSection Phase one"))
        assertEquals("P1", t.prefixFor("10:00:01 LOG doing something"))
        assertEquals("P1-S1", t.prefixFor("10:00:02 LOG startSection Step A"))
        assertEquals("P1-S1", t.prefixFor("10:00:03 PASS ok"))
        assertEquals("P1-S1", t.prefixFor("10:00:04 LOG endSection"))
        assertEquals("P1-S2", t.prefixFor("10:00:05 LOG startSection Step B"))
        assertEquals("P1-S2", t.prefixFor("10:00:06 LOG endSection"))
        assertEquals("P1", t.prefixFor("10:00:07 LOG endSection"))
        // Next top-level section starts phase 2, step counter resets.
        assertEquals("P2", t.prefixFor("10:00:08 LOG startSection Phase two"))
        assertEquals("P2-S1", t.prefixFor("10:00:09 LOG startSection Step A"))
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
