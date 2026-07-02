package com.pysquish.execution

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Parses a script *location* (as it appears in Squish console output or report
 * `<location>` entries) and resolves it to a file **in the opened project**.
 *
 * We resolve by file **name** (Squish often runs from a temp/AppData copy, so the
 * absolute path may not exist in the repo) but disambiguate same-named files —
 * e.g. every `tst_*` has a `test.py` — by matching the **trailing path segments**
 * of the logged location against each candidate. That picks the correct
 * `…/tst_login/test.py` instead of an arbitrary `test.py`.
 */
object SquishScriptLocator {

    /** File extensions of Squish test scripts we link. */
    private const val SCRIPT_EXT = "py|js|pl|rb|tcl"

    /** Matches `something.py` or `dir/something.py:42` and captures path + line. */
    val LOCATION_REGEX = Regex("([A-Za-z0-9_.\\\\/-]+\\.(?:$SCRIPT_EXT))(?::(\\d+))?")

    /** Matches a `line 42` / `line: 42` phrasing (case-insensitive). */
    private val LINE_KEYWORD_REGEX = Regex("line[\\s:]+(\\d+)", RegexOption.IGNORE_CASE)

    /** [fileName] is the leaf; [relPath] is the fuller logged path used to disambiguate. */
    data class Location(val fileName: String, val line: Int, val relPath: String)

    /**
     * Resolves the 1-based line for a location: the `:42` suffix if present,
     * otherwise a `line 42` phrasing found in [context], else 1.
     */
    fun lineFrom(colonGroup: String?, context: String): Int =
        colonGroup?.toIntOrNull()
            ?: LINE_KEYWORD_REGEX.find(context)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 1

    /** Extracts the first script location from [text]. */
    fun parse(text: String?): Location? {
        if (text.isNullOrBlank()) return null
        val m = LOCATION_REGEX.find(text) ?: return null
        val raw = m.groupValues[1].replace('\\', '/')
        val fileName = raw.substringAfterLast('/')
        return Location(fileName, lineFrom(m.groupValues.getOrNull(2), text), raw)
    }

    /**
     * Finds the project file for [fileName], preferring the candidate whose path
     * shares the most trailing segments with [relPath] (so same-named scripts in
     * different test dirs resolve correctly). Repository copy wins over externals.
     */
    fun findInProject(project: Project, fileName: String, relPath: String = fileName): VirtualFile? {
        val candidates = runCatching {
            ReadAction.compute<List<VirtualFile>, RuntimeException> {
                val inProject = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                (inProject.ifEmpty { FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.allScope(project)) })
                    .toList()
            }
        }.getOrDefault(emptyList())

        if (candidates.size <= 1) return candidates.firstOrNull()
        val chosen = bestMatchPath(candidates.map { it.path }, relPath) ?: return candidates.first()
        return candidates.firstOrNull { it.path == chosen } ?: candidates.first()
    }

    /** Picks the path sharing the most trailing segments with [relPath] (pure, testable). */
    fun bestMatchPath(candidatePaths: List<String>, relPath: String): String? {
        if (candidatePaths.size <= 1) return candidatePaths.firstOrNull()
        val wanted = segments(relPath)
        return candidatePaths.maxByOrNull { trailingMatch(segments(it), wanted) }
    }

    private fun segments(path: String): List<String> =
        path.replace('\\', '/').lowercase().split('/').filter { it.isNotEmpty() }

    private fun trailingMatch(have: List<String>, wanted: List<String>): Int {
        var i = have.lastIndex
        var j = wanted.lastIndex
        var count = 0
        while (i >= 0 && j >= 0 && have[i] == wanted[j]) {
            count++; i--; j--
        }
        return count
    }
}
