package com.pysquish.execution

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project

/**
 * Console [Filter] that turns Squish script locations (e.g. `tst_x/test.py:42`)
 * into clickable hyperlinks. The link opens the matching script **in the project**
 * (resolved by file name via [SquishScriptLocator]), not the absolute path printed
 * in the log — so it always lands on the repository copy.
 */
class SquishLocationFilter(private val project: Project) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val matches = SquishScriptLocator.LOCATION_REGEX.findAll(line).toList()
        if (matches.isEmpty()) return null

        val lineStart = entireLength - line.length
        val items = ArrayList<Filter.ResultItem>()
        for (m in matches) {
            val rawPath = m.groupValues[1]
            val fileName = rawPath.substringAfterLast('/').substringAfterLast('\\')
            val lineNo = SquishScriptLocator.lineFrom(m.groupValues.getOrNull(2), line)
            val vf = SquishScriptLocator.findInProject(project, fileName, rawPath) ?: continue
            val info = OpenFileHyperlinkInfo(project, vf, (lineNo - 1).coerceAtLeast(0))
            items.add(Filter.ResultItem(lineStart + m.range.first, lineStart + m.range.last + 1, info))
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}
