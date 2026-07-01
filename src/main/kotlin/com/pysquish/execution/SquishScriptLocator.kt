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
 * We deliberately resolve by file **name**, not by the absolute path found in the
 * log: Squish often runs from a temp/AppData copy, so the absolute path may not
 * exist in the repository. Matching the leaf name against the project index makes
 * the link open the repository's script instead.
 */
object SquishScriptLocator {

    /** File extensions of Squish test scripts we link. */
    private const val SCRIPT_EXT = "py|js|pl|rb|tcl"

    /** Matches `something.py` or `dir/something.py:42` and captures name + line. */
    val LOCATION_REGEX = Regex("([A-Za-z0-9_.\\\\/-]+\\.(?:$SCRIPT_EXT))(?::(\\d+))?")

    data class Location(val fileName: String, val line: Int)

    /** Extracts the first script location from [text] (name is the leaf, no dirs). */
    fun parse(text: String?): Location? {
        if (text.isNullOrBlank()) return null
        val m = LOCATION_REGEX.find(text) ?: return null
        val fileName = m.groupValues[1].substringAfterLast('/').substringAfterLast('\\')
        val line = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 1
        return Location(fileName, line)
    }

    /** Finds a project file whose name equals [fileName]; repository copy wins. */
    fun findInProject(project: Project, fileName: String): VirtualFile? = runCatching {
        ReadAction.compute<VirtualFile?, RuntimeException> {
            FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project)).firstOrNull()
                ?: FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.allScope(project)).firstOrNull()
        }
    }.getOrNull()
}
