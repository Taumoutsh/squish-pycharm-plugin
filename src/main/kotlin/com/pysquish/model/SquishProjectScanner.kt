package com.pysquish.model

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Scans a project for Squish test suites.
 *
 * A suite is any directory that contains a `suite.conf` file. Test cases are the
 * `tst_*` sub-directories. The `suite.conf` `TEST_CASES` key (if present) defines
 * the canonical ordering; otherwise tests are listed alphabetically.
 */
object SquishProjectScanner {

    private val LOG = logger<SquishProjectScanner>()

    private const val MAX_DEPTH = 8

    /** Script file names looked for inside a test-case directory, in priority order. */
    private val SCRIPT_CANDIDATES = listOf("test.py", "test.js", "test.pl", "test.rb", "test.tcl")

    fun scan(project: Project): List<SquishSuite> {
        val roots = collectSearchRoots(project)
        val suiteDirs = LinkedHashSet<Path>()

        for (root in roots) {
            findSuiteConfs(root, 0, suiteDirs)
        }

        return suiteDirs
            .mapNotNull { parseSuite(it) }
            .sortedBy { it.name.lowercase() }
    }

    private fun collectSearchRoots(project: Project): List<Path> {
        val roots = LinkedHashSet<Path>()
        ProjectRootManager.getInstance(project).contentRoots.forEach { vf ->
            runCatching { Path.of(vf.path) }.getOrNull()?.let { roots.add(it) }
        }
        project.basePath?.let { roots.add(Path.of(it)) }
        return roots.filter { it.isDirectory() }
    }

    private fun findSuiteConfs(dir: Path, depth: Int, out: MutableSet<Path>) {
        if (depth > MAX_DEPTH) return
        val name = dir.name
        // Skip noise that never contains suites.
        if (depth > 0 && (name.startsWith(".") || name == "node_modules" || name == "venv" || name == ".venv")) {
            return
        }
        val conf = dir.resolve("suite.conf")
        if (conf.isRegularFile()) {
            out.add(dir)
            // A suite dir won't contain nested suites; stop descending here.
            return
        }
        val children = runCatching {
            Files.newDirectoryStream(dir).use { stream -> stream.filter { it.isDirectory() }.toList() }
        }.getOrElse {
            LOG.debug("Cannot list $dir: ${it.message}")
            emptyList()
        }
        for (child in children) {
            findSuiteConfs(child, depth + 1, out)
        }
    }

    private fun parseSuite(dir: Path): SquishSuite? {
        val conf = dir.resolve("suite.conf")
        if (!conf.isRegularFile()) return null

        val confValues = readConf(conf)
        val language = confValues["LANGUAGE"]

        // Test cases listed in suite.conf preserve author-defined order.
        val ordered = confValues["TEST_CASES"]
            ?.split(Regex("\\s+"))
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val foundOnDisk = runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                stream.filter { it.isDirectory() && it.name.startsWith("tst_") }.map { it.name }.toList()
            }
        }.getOrElse { emptyList() }

        // Union: configured order first, then any extra dirs found on disk.
        val names = LinkedHashSet<String>().apply {
            addAll(ordered)
            addAll(foundOnDisk.sorted())
        }

        val tests = names.mapNotNull { testName ->
            val testDir = dir.resolve(testName)
            if (!testDir.isDirectory()) return@mapNotNull null
            SquishTest(
                name = testName,
                directory = testDir,
                scriptFile = SCRIPT_CANDIDATES
                    .map { testDir.resolve(it) }
                    .firstOrNull { it.isRegularFile() },
            )
        }

        return SquishSuite(
            name = dir.name,
            directory = dir,
            confFile = conf,
            tests = tests,
            language = language,
        )
    }

    /** Minimal `key=value` / `key: value` parser for `suite.conf`. */
    private fun readConf(conf: Path): Map<String, String> {
        val result = HashMap<String, String>()
        runCatching {
            Files.readAllLines(conf).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val sep = line.indexOfFirst { it == '=' || it == ':' }
                if (sep <= 0) return@forEach
                val key = line.substring(0, sep).trim().uppercase()
                val value = line.substring(sep + 1).trim().trim('"')
                result[key] = value
            }
        }.onFailure { LOG.debug("Cannot read $conf: ${it.message}") }
        return result
    }
}
