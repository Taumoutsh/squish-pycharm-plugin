package com.pysquish.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.pysquish.debug.SquishDebugSupport
import com.pysquish.model.SquishSuite
import com.pysquish.model.SquishTest
import com.pysquish.report.SquishRunReport
import com.pysquish.report.SquishXmlReportParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates a single Squish run: optionally starts a squishserver, launches
 * squishrunner, and streams all output into the given [SquishConsole].
 *
 * Only one run is active at a time per instance.
 */
class SquishTestRunner(
    private val project: Project,
    private val console: SquishConsole,
) {
    private val LOG = logger<SquishTestRunner>()

    private val activeRunner = AtomicReference<ProcessHandler?>(null)
    private val activeServer = AtomicReference<ProcessHandler?>(null)

    @Volatile
    private var debugActive = false

    @Volatile
    private var reportDir: Path? = null

    @Volatile
    private var debugBootstrapDir: Path? = null

    val isRunning: Boolean get() = activeRunner.get()?.let { !it.isProcessTerminated } == true

    /** Listener notified when a run starts/finishes so the UI can toggle buttons. */
    var stateListener: ((running: Boolean) -> Unit)? = null

    /** Notified with the parsed report (or null) after a run finishes. */
    var reportListener: ((SquishRunReport?) -> Unit)? = null

    fun run(suite: SquishSuite, test: SquishTest?, debug: Boolean) {
        if (isRunning) {
            console.printError("A Squish test is already running.\n")
            return
        }

        console.clearAll()
        notifyState(true)
        debugActive = debug

        try {
            val debugEnv: Map<String, String>
            val extraPythonPath: List<String>
            if (debug) {
                val setup = SquishDebugSupport.prepare(project, console)
                debugEnv = setup.environment
                extraPythonPath = setup.pythonPath
                debugBootstrapDir = setup.bootstrapDir
            } else {
                debugEnv = emptyMap()
                extraPythonPath = emptyList()
                debugBootstrapDir = null
            }

            val dir = runCatching { Files.createTempDirectory("pysquish-report") }.getOrNull()
            reportDir = dir

            maybeStartServer()

            val command = SquishCommandBuilder.runnerCommand(suite, test, debugEnv, extraPythonPath, dir)
            printCommand(command, debug)
            startRunner(command)
        } catch (e: SquishCommandBuilder.ConfigurationException) {
            console.printError("Configuration error: ${e.message}\n")
            stopServer()
            cleanupDebugBootstrap()
            notifyState(false)
        } catch (e: Exception) {
            LOG.warn("Failed to start Squish run", e)
            console.printError("Failed to start: ${e.message}\n")
            stopServer()
            cleanupDebugBootstrap()
            notifyState(false)
        }
    }

    fun stop() {
        activeRunner.get()?.destroyProcess()
        stopServer()
    }

    private fun maybeStartServer() {
        val serverCmd = SquishCommandBuilder.serverCommand() ?: return
        console.printSystem("Starting squishserver: ${serverCmd.commandLineString}\n")
        val handler = OSProcessHandler(serverCmd)
        // Route through the buffering printer so server output survives filtering.
        handler.addProcessListener(SquishConsolePrinter(console))
        handler.startNotify()
        activeServer.set(handler)
        // Give the server a moment to bind its port before the runner connects.
        Thread.sleep(800)
    }

    private fun startRunner(command: GeneralCommandLine) {
        val handler = OSProcessHandler(command)
        // Colorized, line-buffered printing instead of a raw attach.
        handler.addProcessListener(SquishConsolePrinter(console))
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                console.printSystem("\nSquish runner finished with exit code ${event.exitCode}\n")
                stopServer()
                if (debugActive) SquishDebugSupport.stopDebugServer(project)
                cleanupDebugBootstrap()

                val report = reportDir?.let { SquishXmlReportParser.parse(it) }
                cleanupReportDir()
                ApplicationManager.getApplication().invokeLater { reportListener?.invoke(report) }

                activeRunner.set(null)
                notifyState(false)
            }
        })
        activeRunner.set(handler)
        handler.startNotify()
    }

    private fun cleanupReportDir() = deleteRecursively(reportDir).also { reportDir = null }

    /** Removes the temp `pysquish-debug` bootstrap dir created for a debug run. */
    private fun cleanupDebugBootstrap() = deleteRecursively(debugBootstrapDir).also { debugBootstrapDir = null }

    private fun deleteRecursively(dir: Path?) {
        if (dir == null) return
        runCatching {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun stopServer() {
        activeServer.getAndSet(null)?.let { handler ->
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
    }

    private fun printCommand(command: GeneralCommandLine, debug: Boolean) {
        val mode = if (debug) " (debug)" else ""
        console.printSystem("Running$mode: ${command.commandLineString}\n")
        console.printSystem("Working dir: ${command.workDirectory}\n\n")
    }

    private fun notifyState(running: Boolean) {
        ApplicationManager.getApplication().invokeLater { stateListener?.invoke(running) }
    }
}
