package com.pysquish.execution

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.pysquish.report.SquishLogLevel

/**
 * A console facade that remembers every line it prints (with its Squish level) so
 * the view can be re-rendered when the user changes the level filter. All console
 * writes must go through this facade; otherwise a filter change (which clears and
 * reprints) would drop lines written directly to the underlying [ConsoleView].
 *
 * System lines (level `null`) are always visible and never filtered out.
 */
class SquishConsole(project: Project) : Disposable {

    val view: ConsoleView = TextConsoleBuilderFactory.getInstance()
        .createBuilder(project)
        .apply { addFilter(SquishLocationFilter(project)) }
        .console

    private data class Line(val text: String, val level: SquishLogLevel?, val content: ConsoleViewContentType)

    private val lines = ArrayList<Line>()

    @Volatile
    private var visibleLevels: Set<SquishLogLevel> = ALL_LEVELS

    init {
        Disposer.register(this, view)
    }

    fun clearAll() {
        synchronized(lines) { lines.clear() }
        view.clear()
    }

    fun printSystem(text: String) = add(text, null, ConsoleViewContentType.SYSTEM_OUTPUT)

    fun printError(text: String) = add(text, SquishLogLevel.ERROR, ConsoleViewContentType.ERROR_OUTPUT)

    fun printWarning(text: String) = add(text, SquishLogLevel.WARNING, ConsoleViewContentType.LOG_WARNING_OUTPUT)

    fun printClassified(text: String, level: SquishLogLevel, content: ConsoleViewContentType) =
        add(text, level, content)

    private fun add(text: String, level: SquishLogLevel?, content: ConsoleViewContentType) {
        synchronized(lines) { lines.add(Line(text, level, content)) }
        if (isVisible(level)) view.print(text, content)
    }

    private fun isVisible(level: SquishLogLevel?): Boolean = level == null || level in visibleLevels

    /** Sets which levels are shown and re-renders the buffered content. */
    fun setVisibleLevels(levels: Set<SquishLogLevel>) {
        visibleLevels = levels
        val snapshot = synchronized(lines) { lines.toList() }
        ApplicationManager.getApplication().invokeLater {
            view.clear()
            for (l in snapshot) if (isVisible(l.level)) view.print(l.text, l.content)
        }
    }

    override fun dispose() {}

    companion object {
        /** LOG-level group covers info/traceback/unknown "informational" lines. */
        val LOG_GROUP = setOf(
            SquishLogLevel.LOG, SquishLogLevel.INFO,
            SquishLogLevel.TRACEBACK, SquishLogLevel.UNKNOWN,
        )
        val PASS_GROUP = setOf(SquishLogLevel.PASS)
        val WARNING_GROUP = setOf(SquishLogLevel.WARNING)
        val ERROR_GROUP = setOf(SquishLogLevel.ERROR, SquishLogLevel.FAIL, SquishLogLevel.FATAL)

        val ALL_LEVELS: Set<SquishLogLevel> = LOG_GROUP + PASS_GROUP + WARNING_GROUP + ERROR_GROUP
    }
}
