package com.pysquish.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
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
 * squishrunner, and streams all output into the given [ConsoleView].
 *
 * Only one run is active at a time per instance.
 */
class SquishTestRunner(
    private val project: Project,
    private val console: ConsoleView,
) {
    private val LOG = logger<SquishTestRunner>()

    private val activeRunner = AtomicReference<ProcessHandler?>(null)
    private val activeServer = AtomicReference<ProcessHandler?>(null)

    @Volatile
    private var debugActive = false

    @Volatile
    private var reportDir: Path? = null

    val isRunning: Boolean get() = activeRunner.get()?.let { !it.isProcessTerminated } == true

    /** Listener notified when a run starts/finishes so the UI can toggle buttons. */
    var stateListener: ((running: Boolean) -> Unit)? = null

    /** Notified with the parsed report (or null) after a run finishes. */
    var reportListener: ((SquishRunReport?) -> Unit)? = null

    fun run(suite: SquishSuite, test: SquishTest?, debug: Boolean) {
        if (isRunning) {
            console.print("A Squish test is already running.\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }

        console.clear()
        notifyState(true)
        debugActive = debug

        try {
            val debugEnv: Map<String, String>
            val extraPythonPath: List<String>
            if (debug) {
                val setup = SquishDebugSupport.prepare(project, console)
                debugEnv = setup.environment
                extraPythonPath = setup.pythonPath
            } else {
                debugEnv = emptyMap()
                extraPythonPath = emptyList()
            }

            val dir = runCatching { Files.createTempDirectory("pysquish-report") }.getOrNull()
            reportDir = dir

            maybeStartServer()

            val command = SquishCommandBuilder.runnerCommand(suite, test, debugEnv, extraPythonPath, dir)
            printCommand(command, debug)
            startRunner(command)
        } catch (e: SquishCommandBuilder.ConfigurationException) {
            console.print("Configuration error: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            stopServer()
            notifyState(false)
        } catch (e: Exception) {
            LOG.warn("Failed to start Squish run", e)
            console.print("Failed to start: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            stopServer()
            notifyState(false)
        }
    }

    fun stop() {
        activeRunner.get()?.destroyProcess()
        stopServer()
    }

    private fun maybeStartServer() {
        val serverCmd = SquishCommandBuilder.serverCommand() ?: return
        console.print("Starting squishserver: ${serverCmd.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val handler = OSProcessHandler(serverCmd)
        console.attachToProcess(handler)
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
                console.print(
                    "\nSquish runner finished with exit code ${event.exitCode}\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT,
                )
                stopServer()
                if (debugActive) SquishDebugSupport.stopDebugServer(project)

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

    private fun cleanupReportDir() {
        val dir = reportDir ?: return
        reportDir = null
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
        console.print("Running$mode: ${command.commandLineString}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("Working dir: ${command.workDirectory}\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private fun notifyState(running: Boolean) {
        ApplicationManager.getApplication().invokeLater { stateListener?.invoke(running) }
    }
}
