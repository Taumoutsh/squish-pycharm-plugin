package com.pysquish.execution

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.pysquish.report.SquishLogLevel
import java.awt.Font

/** Console colors per Squish log level (theme-aware light/dark pairs). */
object SquishConsoleColors {

    private fun ct(name: String, fg: JBColor, bold: Boolean = false): ConsoleViewContentType =
        ConsoleViewContentType(
            name,
            TextAttributes(fg, null, null, null, if (bold) Font.BOLD else Font.PLAIN),
        )

    private val PASS = ct("PYSQUISH_PASS", JBColor(0x1A8F3C, 0x5FAD5F), bold = true)
    private val INFO = ct("PYSQUISH_INFO", JBColor(0x2A6FDB, 0x6897BB))
    private val LOG = ct("PYSQUISH_LOG", JBColor(0x7A7A7A, 0x999999))

    fun contentType(level: SquishLogLevel): ConsoleViewContentType = when (level) {
        SquishLogLevel.PASS -> PASS
        SquishLogLevel.INFO -> INFO
        SquishLogLevel.LOG -> LOG
        SquishLogLevel.WARNING -> ConsoleViewContentType.LOG_WARNING_OUTPUT
        SquishLogLevel.ERROR,
        SquishLogLevel.FAIL,
        SquishLogLevel.FATAL -> ConsoleViewContentType.ERROR_OUTPUT
        SquishLogLevel.UNKNOWN -> ConsoleViewContentType.NORMAL_OUTPUT
    }
}

/**
 * Classifies a single console line onto a [SquishLogLevel] by looking at its
 * leading tokens (tolerating a leading timestamp and simple decoration).
 *
 * Call [SquishConsolePrinter.stripControl] first so ANSI escapes don't hide the
 * level token. The level keywords live entirely in [SquishLogLevel.from].
 */
object SquishLogClassifier {
    fun classify(rawLine: String): SquishLogLevel {
        val line = rawLine.trim()
        if (line.isEmpty()) return SquishLogLevel.UNKNOWN
        // Split on whitespace only (NOT colons) so a leading ISO timestamp like
        // 2024-01-15T10:23:45 stays one token and the level (e.g. LOG) is next.
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (token in tokens.take(4)) {
            val level = SquishLogLevel.from(token.trim('[', ']', '*', '-', '(', ')'))
            if (level != SquishLogLevel.UNKNOWN) return level
        }
        return SquishLogLevel.UNKNOWN
    }
}

/**
 * A [ProcessAdapter] that buffers process output into whole lines, strips ANSI
 * escape sequences, and prints each line to [console] with a color chosen from
 * its Squish log level. Used instead of `ConsoleView.attachToProcess` so we
 * control per-line coloring; that also means escape codes must be stripped here
 * (the console does not decode them when we call `print`).
 */
class SquishConsolePrinter(private val console: ConsoleView) : ProcessAdapter() {

    private val stdout = StringBuilder()
    private val stderr = StringBuilder()

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val isError = outputType == ProcessOutputTypes.STDERR
        val buffer = if (isError) stderr else stdout
        buffer.append(event.text)
        flushCompleteLines(buffer, isError)
    }

    override fun processTerminated(event: ProcessEvent) {
        flushRemaining()
    }

    private fun flushCompleteLines(buffer: StringBuilder, isError: Boolean) {
        var newline = buffer.indexOf("\n")
        while (newline >= 0) {
            val line = buffer.substring(0, newline + 1)
            buffer.delete(0, newline + 1)
            printLine(line, isError)
            newline = buffer.indexOf("\n")
        }
    }

    private fun flushRemaining() {
        if (stdout.isNotEmpty()) {
            printLine(stdout.toString(), isError = false); stdout.setLength(0)
        }
        if (stderr.isNotEmpty()) {
            printLine(stderr.toString(), isError = true); stderr.setLength(0)
        }
    }

    private fun printLine(rawLine: String, isError: Boolean) {
        val line = unescape(stripControl(rawLine))
        val level = SquishLogClassifier.classify(line)
        val contentType = when {
            level != SquishLogLevel.UNKNOWN -> SquishConsoleColors.contentType(level)
            isError -> ConsoleViewContentType.ERROR_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
        console.print(line, contentType)
    }

    companion object {
        // ESC (\x1B) + CSI sequence (colors, cursor moves, ...), or a stray ESC.
        private val ANSI = Regex("\\x1B\\[[0-9;?]*[ -/]*[@-~]|\\x1B")

        /** Removes ANSI escape sequences and carriage returns for a clean line. */
        fun stripControl(s: String): String = ANSI.replace(s.replace("\r", ""), "")

        /** Unescapes Squish's backslash escapes (\\, \t, \n, \") for display. */
        fun unescape(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\\' -> { sb.append('\\'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> i += 2
                        '"' -> { sb.append('"'); i += 2 }
                        else -> { sb.append(c); i += 1 }
                    }
                } else {
                    sb.append(c); i += 1
                }
            }
            return sb.toString()
        }
    }
}
