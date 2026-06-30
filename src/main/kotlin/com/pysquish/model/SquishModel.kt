package com.pysquish.model

import java.nio.file.Path

/**
 * A single Squish test case (a `tst_*` directory containing a test script).
 */
data class SquishTest(
    val name: String,
    val directory: Path,
    val scriptFile: Path?,
)

/**
 * A Squish test suite: a directory containing a `suite.conf` and one or more
 * `tst_*` test-case directories.
 */
data class SquishSuite(
    val name: String,
    val directory: Path,
    val confFile: Path,
    val tests: List<SquishTest>,
    val language: String?,
) {
    override fun toString(): String = name
}
