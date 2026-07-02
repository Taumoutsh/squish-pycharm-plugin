package com.pysquish.execution

/**
 * Derives the console `P<phase>-S<step>` tag from explicit Squish log markers.
 *
 * A line whose content matches `Start Section: Phase <p>, Step <s>` sets the
 * current phase and step; every subsequent line is tagged with the latest
 * values, until the next such marker. Nothing is shown before the first marker.
 * Section nesting/depth is deliberately **not** used.
 */
class SquishSectionTracker {

    private var phase: Int? = null
    private var step: Int? = null

    // "... Start Section: Phase 2, Step 3 ..." — case-insensitive, flexible
    // separators between the keywords and their numbers.
    private val markerRegex = Regex(
        "start\\s*section\\b[^\\n]*?phase\\s*(\\d+)[^\\n]*?step\\s*(\\d+)",
        RegexOption.IGNORE_CASE,
    )

    fun prefixFor(line: String): String? {
        markerRegex.find(line)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { phase = it }
            match.groupValues[2].toIntOrNull()?.let { step = it }
        }
        val p = phase ?: return null
        val s = step ?: return "P$p"
        return "P$p-S$s"
    }

    fun reset() {
        phase = null
        step = null
    }
}
