package com.pysquish.debug

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.pysquish.settings.SquishSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Wires the running Squish/Python interpreter to the PyCharm debugger using the
 * standard `pydevd` *remote attach* model:
 *
 *  1. PyCharm starts a "Python Debug Server" that listens on a TCP port.
 *  2. The Squish interpreter imports `pydevd_pycharm` and calls `settrace(...)`,
 *     which connects back to that port. Breakpoints set in the IDE are then honored.
 *
 * To avoid editing the user's test scripts, PySquish drops a `sitecustomize.py`
 * on `PYTHONPATH` that performs the attach automatically when the
 * `PYSQUISH_DEBUG` environment variable is set. A `pysquish_debug` module with an
 * explicit `attach()` is also provided as a manual fallback (for interpreters
 * started with `-S`/no site).
 */
object SquishDebugSupport {

    private val LOG = logger<SquishDebugSupport>()

    data class Setup(val environment: Map<String, String>, val pythonPath: List<String>)

    fun prepare(project: Project, console: ConsoleView): Setup {
        val settings = SquishSettings.state()
        val host = settings.debugHost?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        val port = settings.debugPort

        startDebugServer(project, console, port)

        val bootstrapDir = writeBootstrap(host, port)
        val pythonPath = buildList {
            add(bootstrapDir.toString())
            resolvePydevdDir(settings.pydevdPath)?.let {
                add(it.toString())
                console.print("[PySquish] Using pydevd from: $it\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            } ?: console.print(
                "[PySquish] WARNING: pydevd_pycharm not located. Set 'pydevd_pycharm path' in settings " +
                    "or `pip install pydevd-pycharm` into the Squish interpreter.\n",
                ConsoleViewContentType.LOG_WARNING_OUTPUT,
            )
        }

        val env = mapOf(
            "PYSQUISH_DEBUG" to "1",
            "PYSQUISH_DEBUG_HOST" to host,
            "PYSQUISH_DEBUG_PORT" to port.toString(),
        )

        console.print(
            "[PySquish] Debug attach configured for $host:$port. " +
                "Set breakpoints in your test scripts; the run will pause when they are hit.\n\n",
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )
        return Setup(env, pythonPath)
    }

    /**
     * Starts PyCharm's "Python Debug Server" so it is listening before the test
     * connects. Best-effort: if the configuration type cannot be found or started,
     * prints manual instructions and continues.
     */
    private fun startDebugServer(project: Project, console: ConsoleView, port: Int) {
        val type = findRemoteDebugType()
        if (type == null) {
            printManualServerHelp(console, port)
            return
        }
        try {
            val factory = type.configurationFactories.firstOrNull() ?: run {
                printManualServerHelp(console, port); return
            }
            val runManager = RunManager.getInstance(project)
            val settings = runManager.createConfiguration("PySquish Debug Server", factory)
            applyPort(settings.configuration, port)

            ApplicationManager.getApplication().invokeLater {
                runCatching {
                    ExecutionUtil.runConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
                }.onFailure {
                    LOG.info("Could not auto-start Python Debug Server", it)
                    printManualServerHelp(console, port)
                }
            }
            console.print(
                "[PySquish] Starting Python Debug Server on port $port...\n",
                ConsoleViewContentType.SYSTEM_OUTPUT,
            )
        } catch (e: Exception) {
            LOG.info("Could not auto-start Python Debug Server", e)
            printManualServerHelp(console, port)
        }
    }

    private fun findRemoteDebugType(): ConfigurationType? =
        ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull {
            it.id == "PyRemoteDebugConfigurationType"
        }

    /** Sets host/port on a PyRemoteDebugConfiguration via reflection (field names vary by version). */
    private fun applyPort(configuration: Any, port: Int) {
        val cls = configuration.javaClass
        // Try setter methods first.
        for (name in listOf("setPort")) {
            runCatching { cls.getMethod(name, Int::class.javaPrimitiveType).invoke(configuration, port); return }
            runCatching { cls.getMethod(name, String::class.java).invoke(configuration, port.toString()); return }
        }
        // Fall back to public fields PORT / port.
        for (field in listOf("PORT", "port")) {
            runCatching {
                val f = cls.getField(field)
                if (f.type == Int::class.javaPrimitiveType || f.type == Integer::class.java) {
                    f.setInt(configuration, port)
                } else {
                    f.set(configuration, port.toString())
                }
                return
            }
        }
        LOG.info("Could not set port on ${cls.name}; using its default. Configure it manually if needed.")
    }

    private fun printManualServerHelp(console: ConsoleView, port: Int) {
        console.print(
            "[PySquish] Could not auto-start the Python Debug Server. Create a run configuration of type " +
                "'Python Debug Server' listening on port $port and start it manually, then re-run in debug mode.\n",
            ConsoleViewContentType.LOG_WARNING_OUTPUT,
        )
    }

    /** Writes the sitecustomize + manual-attach modules to a temp dir, returns that dir. */
    private fun writeBootstrap(host: String, port: Int): Path {
        val dir = Files.createTempDirectory("pysquish-debug")
        dir.toFile().deleteOnExit()

        val attachModule = """
            import os

            def attach(host=None, port=None, suspend=False):
                host = host or os.environ.get("PYSQUISH_DEBUG_HOST", "$host")
                port = int(port or os.environ.get("PYSQUISH_DEBUG_PORT", "$port"))
                try:
                    import pydevd_pycharm
                    pydevd_pycharm.settrace(
                        host, port=port,
                        stdoutToServer=True, stderrToServer=True,
                        suspend=suspend,
                    )
                    return True
                except Exception as exc:  # noqa: BLE001
                    print("[PySquish] could not attach debugger:", exc)
                    return False
        """.trimIndent()

        // Auto-attach hook: site.py imports `sitecustomize` for every interpreter
        // that runs site initialization, so this triggers without editing tests.
        val siteCustomize = """
            import os

            if os.environ.get("PYSQUISH_DEBUG") == "1":
                try:
                    import pysquish_debug
                    pysquish_debug.attach()
                except Exception as exc:  # noqa: BLE001
                    print("[PySquish] debug bootstrap failed:", exc)
        """.trimIndent()

        Files.writeString(dir.resolve("pysquish_debug.py"), attachModule + "\n")
        Files.writeString(dir.resolve("sitecustomize.py"), siteCustomize + "\n")
        return dir
    }

    /**
     * Resolves the directory that contains `pydevd_pycharm.py`. Uses the user
     * setting when provided, otherwise probes the bundled PyCharm Python helpers.
     */
    private fun resolvePydevdDir(configured: String?): Path? {
        configured?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
            val path = Path.of(p)
            if (containsPydevd(path)) return path
        }

        val home = Path.of(PathManager.getHomePath())
        val candidates = listOf(
            "plugins/python-ce/helpers/pydev",
            "plugins/python/helpers/pydev",
            "plugins/Pythonid/helpers/pydev",
            "plugins/python-ce/helpers/pycharm",
            "plugins/python/helpers/pycharm",
        )
        for (rel in candidates) {
            val dir = home.resolve(rel)
            if (containsPydevd(dir)) return dir
        }
        return null
    }

    private fun containsPydevd(dir: Path): Boolean =
        dir.isDirectory() &&
            (dir.resolve("pydevd_pycharm.py").exists() || dir.resolve("pydevd.py").exists())
}
