package com.pysquish.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SquishScriptLocatorTest {

    @Test
    fun `parses filename and colon line`() {
        val loc = SquishScriptLocator.parse("failed at tst_login/test.py:42 in main")!!
        assertEquals("test.py", loc.fileName)
        assertEquals(42, loc.line)
    }

    @Test
    fun `parses a line keyword when there is no colon line`() {
        val loc = SquishScriptLocator.parse("test.py, line 7: boom")!!
        assertEquals("test.py", loc.fileName)
        assertEquals(7, loc.line)
    }

    @Test
    fun `defaults to line 1 without any line info`() {
        assertEquals(1, SquishScriptLocator.parse("see test.py")!!.line)
    }

    @Test
    fun `null when no script location present`() {
        assertNull(SquishScriptLocator.parse("no location at all"))
    }

    @Test
    fun `colon line wins over a line keyword`() {
        assertEquals(5, SquishScriptLocator.lineFrom("5", "line 99"))
        assertEquals(99, SquishScriptLocator.lineFrom(null, "error on line 99"))
        assertEquals(1, SquishScriptLocator.lineFrom(null, "no number here"))
    }

    @Test
    fun `disambiguates same-named scripts by trailing path segments`() {
        val candidates = listOf(
            "/proj/suite_a/tst_login/test.py",
            "/proj/suite_a/tst_logout/test.py",
        )
        assertEquals(
            "/proj/suite_a/tst_logout/test.py",
            SquishScriptLocator.bestMatchPath(candidates, "tst_logout/test.py"),
        )
        // An absolute logged path (temp copy) still resolves by its tail segments.
        assertEquals(
            "/proj/suite_a/tst_login/test.py",
            SquishScriptLocator.bestMatchPath(candidates, "C:/tmp/run/suite_a/tst_login/test.py"),
        )
    }
}
