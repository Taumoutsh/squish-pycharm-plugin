package com.pysquish.execution

import com.pysquish.report.SquishLogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SquishConsolePrinterTest {

    // Squish stdout format: "<ISO timestamp>\t<LEVEL>\t<location>\t<message>".
    @Test
    fun `classifies level after an ISO timestamp`() {
        assertEquals(
            SquishLogLevel.LOG,
            SquishLogClassifier.classify("2024-01-15T10:23:45\tLOG\tC:/loc\tHello"),
        )
        assertEquals(
            SquishLogLevel.PASS,
            SquishLogClassifier.classify("2024-01-15T10:23:45\tPASS\tC:/loc\tok"),
        )
        assertEquals(
            SquishLogLevel.FAIL,
            SquishLogClassifier.classify("2024-01-15T10:23:45    FAIL    C:/loc    boom"),
        )
    }

    @Test
    fun `unknown when no level token is present`() {
        assertEquals(
            SquishLogLevel.UNKNOWN,
            SquishLogClassifier.classify("2024-01-15T10:23:45\tplain output line"),
        )
    }

    @Test
    fun `unescape collapses doubled backslashes and common escapes`() {
        // Source "C:\\\\temp" is the two-char sequence C:\\temp; expect C:\temp.
        assertEquals("C:\\temp", SquishConsolePrinter.unescape("C:\\\\temp"))
        assertEquals("a\tb", SquishConsolePrinter.unescape("a\\tb"))
        assertEquals("say \"hi\"", SquishConsolePrinter.unescape("say \\\"hi\\\""))
    }
}
