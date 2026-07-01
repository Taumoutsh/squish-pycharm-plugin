package com.pysquish.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.pysquish.model.SquishSuite
import com.pysquish.model.SquishTest
import com.pysquish.settings.SquishSettings

/**
 * Builds [GeneralCommandLine]s for squishrunner / squishserver from the current
 * [SquishSettings] and a chosen suite/test.
 */
object SquishCommandBuilder {

    class ConfigurationException(message: String) : Exception(message)

    /**
     * @param debugEnv extra environment variables (e.g. for remote debugging) and
     *                 PYTHONPATH entries that should be merged into the process.
     */
    fun runnerCommand(
        suite: SquishSuite,
        test: SquishTest?,
        debugEnv: Map<String, String> = emptyMap(),
        extraPythonPath: List<String> = emptyList(),
    ): GeneralCommandLine {
        val settings = SquishSettings.state()
        val runner = settings.squishRunnerPath?.trim().orEmpty()
        if (runner.isEmpty()) {
            throw ConfigurationException(
                "squishrunner path is not configured. Set it in Settings | Tools | PySquish.",
            )
        }

        val cmd = GeneralCommandLine(runner)
        cmd.withWorkDirectory(suite.directory.toFile())

        // Connection options must precede --testsuite/--testcase; squishrunner
        // rejects --host/--port that come after the suite. A blank host is
        // omitted so squishrunner falls back to its own default.
        if (settings.startServer || settings.connectToServer) {
            settings.serverHost?.takeIf { it.isNotBlank() }?.let { host ->
                cmd.addParameter("--host")
                cmd.addParameter(host)
            }
            cmd.addParameter("--port")
            cmd.addParameter(settings.serverPort.toString())
        }

        cmd.addParameter("--testsuite")
        cmd.addParameter(suite.directory.toString())

        if (test != null) {
            cmd.addParameter("--testcase")
            cmd.addParameter(test.name)
        }

        parseArgs(settings.extraRunnerArgs).forEach { cmd.addParameter(it) }

        applyEnvironment(cmd, debugEnv, extraPythonPath)
        return cmd
    }

    fun serverCommand(): GeneralCommandLine? {
        val settings = SquishSettings.state()
        if (!settings.startServer) return null
        val server = settings.squishServerPath?.trim().orEmpty()
        if (server.isEmpty()) {
            throw ConfigurationException(
                "'Start a local squishserver' is enabled but the squishserver path is empty.",
            )
        }
        return GeneralCommandLine(server).apply {
            addParameter("--port")
            addParameter(settings.serverPort.toString())
        }
    }

    private fun applyEnvironment(
        cmd: GeneralCommandLine,
        debugEnv: Map<String, String>,
        extraPythonPath: List<String>,
    ) {
        if (extraPythonPath.isNotEmpty()) {
            val existing = System.getenv("PYTHONPATH")
            val merged = (extraPythonPath + listOfNotNull(existing))
                .filter { it.isNotBlank() }
                .joinToString(java.io.File.pathSeparator)
            cmd.environment["PYTHONPATH"] = merged
        }
        debugEnv.forEach { (k, v) -> cmd.environment[k] = v }
    }

    /** Splits a CLI string honoring simple single/double quoting. */
    fun parseArgs(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val result = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (c in raw) {
            when {
                quote != null -> if (c == quote) quote = null else sb.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> if (sb.isNotEmpty()) {
                    result.add(sb.toString()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }
}
