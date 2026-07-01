package com.pysquish.model

import com.samskivert.mustache.Mustache
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Creates new Squish suites and test cases on disk.
 *
 * The pure helpers ([suiteDirName], [testDirName], [validateName], [suiteConf],
 * [addTestCase], [renderTestScript]) carry the interesting logic and are unit
 * tested; [createSuite] / [createTest] wrap them with filesystem I/O and return
 * a [Result] so the UI can surface errors.
 *
 * `suite.conf` is produced from a built-in template; the test **script** is
 * rendered from a (user-editable) Mustache template — bundled default at
 * `templates/test.py.mustache`, overridable via settings.
 */
object SquishScaffolder {

    /** Allowed characters for a raw suite/test name (prefixes included). */
    private val NAME_REGEX = Regex("[A-Za-z0-9_-]+")

    private val SCRIPT_BY_LANGUAGE = mapOf(
        "python" to "test.py",
        "javascript" to "test.js",
        "js" to "test.js",
        "perl" to "test.pl",
        "ruby" to "test.rb",
        "tcl" to "test.tcl",
    )

    /** Squish suite directories are conventionally prefixed `suite_`. */
    fun suiteDirName(rawName: String): String {
        val cleaned = rawName.trim()
        return if (cleaned.startsWith("suite_")) cleaned else "suite_$cleaned"
    }

    /** Squish test-case directories are conventionally prefixed `tst_`. */
    fun testDirName(rawName: String): String {
        val cleaned = rawName.trim()
        return if (cleaned.startsWith("tst_")) cleaned else "tst_$cleaned"
    }

    /** @return an error message if [rawName] is not a valid suite/test name, else null. */
    fun validateName(rawName: String): String? {
        val cleaned = rawName.trim()
        if (cleaned.isEmpty()) return "Name must not be empty."
        if (!NAME_REGEX.matches(cleaned)) {
            return "Use only letters, digits, '_' and '-' (no spaces)."
        }
        return null
    }

    private fun scriptFileName(language: String): String =
        SCRIPT_BY_LANGUAGE[language.trim().lowercase()] ?: "test.py"

    /** Built-in `suite.conf` contents. `TEST_CASES` is omitted until a test is added. */
    fun suiteConf(aut: String, language: String = "Python"): String = buildString {
        appendLine("AUT=${aut.trim()}")
        appendLine("LANGUAGE=$language")
        appendLine("OBJECTMAPSTYLE=script")
        appendLine("VERSION=3")
        appendLine("WRAPPERS=Qt")
    }

    /**
     * Returns [confText] with [testName] registered in its `TEST_CASES` value.
     * Preserves the original separator (`=`/`: `) and appends without duplicating.
     * If no `TEST_CASES` line exists, one is inserted in alphabetical key order.
     */
    fun addTestCase(confText: String, testName: String): String {
        val lines = confText.split("\n").toMutableList()
        val idx = lines.indexOfFirst { line ->
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            sep > 0 && line.substring(0, sep).trim().equals("TEST_CASES", ignoreCase = true)
        }
        if (idx >= 0) {
            val line = lines[idx]
            val sepPos = line.indexOfFirst { it == '=' || it == ':' }
            val sep = line[sepPos]
            val tokens = line.substring(sepPos + 1).split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .toMutableList()
            if (testName !in tokens) tokens.add(testName)
            lines[idx] = "TEST_CASES$sep${tokens.joinToString(" ")}"
            return lines.joinToString("\n")
        }

        // No TEST_CASES line — insert it in alphabetical key position.
        val newLine = "TEST_CASES=$testName"
        val insertAt = lines.indexOfFirst { line ->
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            sep > 0 && line.substring(0, sep).trim().uppercase() > "TEST_CASES"
        }
        if (insertAt >= 0) lines.add(insertAt, newLine) else {
            // Append after the last non-blank line so we don't leave a gap.
            val lastKey = lines.indexOfLast { it.isNotBlank() }
            lines.add(lastKey + 1, newLine)
        }
        return lines.joinToString("\n")
    }

    /** Renders a test script from a Mustache [templateText]. */
    fun renderTestScript(templateText: String, context: Map<String, Any>): String =
        Mustache.compiler().defaultValue("").compile(templateText).execute(context)

    /** The packaged default test-script template. */
    fun bundledTestTemplate(): String =
        javaClass.getResourceAsStream("/templates/test.py.mustache")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: "# -*- coding: utf-8 -*-\n# {{testName}}\n\n\ndef main():\n    pass\n"

    /**
     * Resolves the test template text: the file at [customPath] when it is a
     * non-blank, readable path, otherwise the [bundledTestTemplate].
     */
    fun resolveTestTemplate(customPath: String?): String {
        val p = customPath?.trim().orEmpty()
        if (p.isNotEmpty()) {
            val path = runCatching { Path.of(p) }.getOrNull()
            if (path != null && Files.isRegularFile(path)) {
                runCatching { Files.readString(path) }.getOrNull()?.let { return it }
            }
        }
        return bundledTestTemplate()
    }

    /**
     * Creates `<parentDir>/suite_<name>/` with a `suite.conf`.
     * @return the created suite directory.
     */
    fun createSuite(parentDir: Path, rawName: String, aut: String): Result<Path> = runCatching {
        val dirName = suiteDirName(rawName)
        val suiteDir = parentDir.resolve(dirName)
        require(!Files.exists(suiteDir)) { "A folder named \"$dirName\" already exists here." }
        Files.createDirectories(suiteDir)
        Files.writeString(suiteDir.resolve("suite.conf"), suiteConf(aut))
        suiteDir
    }

    /**
     * Creates `<suiteDir>/tst_<name>/<script>` from [templateText] and registers
     * the test in the suite's `suite.conf` `TEST_CASES`.
     * @return the created script file.
     */
    fun createTest(
        suiteDir: Path,
        rawName: String,
        templateText: String,
        suiteName: String,
        aut: String,
        language: String,
    ): Result<Path> = runCatching {
        val dirName = testDirName(rawName)
        val testDir = suiteDir.resolve(dirName)
        require(!Files.exists(testDir)) { "A test named \"$dirName\" already exists in this suite." }
        Files.createDirectories(testDir)

        val script = testDir.resolve(scriptFileName(language))
        val context = mapOf(
            "testName" to dirName,
            "suiteName" to suiteName,
            "aut" to aut.trim(),
            "language" to language,
            "date" to LocalDate.now().toString(),
            "hasAut" to aut.isNotBlank(),
        )
        Files.writeString(script, renderTestScript(templateText, context))

        val conf = suiteDir.resolve("suite.conf")
        if (Files.isRegularFile(conf)) {
            Files.writeString(conf, addTestCase(Files.readString(conf), dirName))
        }
        script
    }
}
