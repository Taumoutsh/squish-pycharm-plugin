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
 * TODO: tune the token set against real `squishrunner --reportgen stdout`
 * output; the level keywords live entirely in [SquishLogLevel.from].
 */
object SquishLogClassifier {
    fun classify(rawLine: String): SquishLogLevel {
        val line = rawLine.trim()
        if (line.isEmpty()) return SquishLogLevel.UNKNOWN
        val tokens = line.split(Regex("[\\s:]+")).filter { it.isNotEmpty() }
        for (token in tokens.take(3)) {
            val level = SquishLogLevel.from(token.trim('[', ']', '*', '-', '(', ')'))
            if (level != SquishLogLevel.UNKNOWN) return level
        }
        return SquishLogLevel.UNKNOWN
    }
}

/**
 * A [ProcessAdapter] that buffers process output into whole lines and prints
 * each one to [console] with a color chosen from its Squish log level. Used
 * instead of `ConsoleView.attachToProcess` so we control per-line coloring.
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

    private fun printLine(line: String, isError: Boolean) {
        val level = SquishLogClassifier.classify(line)
        val contentType = when {
            level != SquishLogLevel.UNKNOWN -> SquishConsoleColors.contentType(level)
            isError -> ConsoleViewContentType.ERROR_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
        console.print(line, contentType)
    }
}
