package com.pysquish.report

/**
 * Log/result levels emitted by Squish. Ordered roughly by severity; the parser
 * and console classifier normalize Squish's `type` tokens onto these.
 */
enum class SquishLogLevel {
    LOG,
    PASS,
    INFO,
    WARNING,
    ERROR,
    FAIL,
    FATAL,
    TRACEBACK,
    UNKNOWN;

    /** True for levels that make a test/section count as failed. */
    val isFailure: Boolean
        get() = this == FAIL || this == ERROR || this == FATAL

    companion object {
        /** Maps a Squish `type` token (case-insensitive) onto a level. */
        fun from(token: String?): SquishLogLevel {
            return when (token?.trim()?.uppercase()) {
                "LOG" -> LOG
                "PASS", "PASSED" -> PASS
                "INFO", "DETAIL" -> INFO
                "WARNING", "WARN" -> WARNING
                "ERROR" -> ERROR
                "FAIL", "FAILED" -> FAIL
                "FATAL" -> FATAL
                else -> UNKNOWN
            }
        }
    }
}

/** Overall verdict for a test case. */
enum class SquishVerdict { PASS, FAIL, UNKNOWN }

/**
 * A node in a test's report tree. Either a leaf [Entry] (a single log/result
 * line) or a [Section] that can contain further nodes (from Squish's
 * `startSection`/`endSection`).
 */
sealed interface SquishReportNode {
    /** A single message / verification result. */
    data class Entry(
        val level: SquishLogLevel,
        val message: String,
        val detail: String? = null,
        val timestamp: String? = null,
    ) : SquishReportNode

    /** A screenshot captured on failure. */
    data class Image(
        val path: java.nio.file.Path,
    ) : SquishReportNode

    /** A foldable section; [containsFailure] is precomputed for auto-expand. */
    data class Section(
        val title: String,
        val children: MutableList<SquishReportNode> = mutableListOf(),
        var timestamp: String? = null,
    ) : SquishReportNode {
        val containsFailure: Boolean
            get() = children.any {
                when (it) {
                    is Entry -> it.level.isFailure
                    is Image -> false
                    is Section -> it.containsFailure
                }
            }
    }
}

/**
 * The parsed report for a single test case: its verdict and the root of its
 * section/entry tree.
 */
data class SquishTestReport(
    val name: String,
    val verdict: SquishVerdict,
    val root: SquishReportNode.Section,
)

/** The whole run: every test case found in the report, in document order. */
data class SquishRunReport(
    val tests: List<SquishTestReport>,
) {
    fun verdictOf(testName: String): SquishVerdict =
        tests.firstOrNull { it.name == testName }?.verdict ?: SquishVerdict.UNKNOWN
}
