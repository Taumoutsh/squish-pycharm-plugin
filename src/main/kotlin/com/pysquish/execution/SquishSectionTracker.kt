package com.pysquish.execution

/**
 * Tracks Squish `startSection`/`endSection` markers seen in the console stream and
 * produces an abbreviated `P<phase>-S<step>` tag.
 *
 * The first nesting level is the **phase**, the second is the **step**:
 * - a top-level `startSection` increments the phase and resets the step;
 * - a `startSection` nested one deeper increments the step.
 *
 * [prefixFor] returns the tag for the current line, or `null` when we are outside
 * any section (so nothing is shown, per the requirement).
 */
class SquishSectionTracker {

    private var depth = 0
    private var phase = 0
    private var step = 0

    private val startRegex = Regex("start\\s*section", RegexOption.IGNORE_CASE)
    private val endRegex = Regex("end\\s*section", RegexOption.IGNORE_CASE)

    fun prefixFor(line: String): String? {
        val isStart = startRegex.containsMatchIn(line)
        val isEnd = !isStart && endRegex.containsMatchIn(line)

        if (isStart) {
            depth++
            when {
                depth == 1 -> { phase++; step = 0 }   // new phase resets the step counter
                depth == 2 -> step++                  // steps are numbered cumulatively within a phase
            }
        }

        // Tag reflects the section the current line belongs to (computed before an
        // endSection pops the level). The step is only shown while inside a step
        // (depth >= 2); at phase level we show just "P<phase>"; outside, nothing.
        val tag = when {
            depth == 0 -> null
            depth == 1 -> "P$phase"
            else -> "P$phase-S$step"
        }

        if (isEnd && depth > 0) depth--

        return tag
    }

    fun reset() {
        depth = 0; phase = 0; step = 0
    }
}
