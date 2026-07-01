package com.pysquish.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBTabbedPane
import com.pysquish.execution.SquishConsole
import com.pysquish.execution.SquishTestRunner
import com.pysquish.model.SquishProjectScanner
import com.pysquish.model.SquishScaffolder
import com.pysquish.model.SquishSuite
import com.pysquish.model.SquishTest
import com.pysquish.report.SquishLogLevel
import com.pysquish.report.SquishReportNode
import com.pysquish.report.SquishRunReport
import com.pysquish.report.SquishVerdict
import com.pysquish.settings.SquishSettings
import com.pysquish.settings.SquishSettingsConfigurable
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The PySquish tool window: a suite combo box, a per-test run/debug button list,
 * and a console that streams squishrunner output.
 */
class SquishToolWindowPanel(private val project: Project) :
    SimpleToolWindowPanel(false, true), com.intellij.openapi.Disposable {

    private val console = SquishConsole(project)

    private val runner = SquishTestRunner(project, console)

    private val reportPanel = SquishReportPanel(project)

    private val suiteCombo = JComboBox<SquishSuite>()
    private val testListPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val statusLabel = JBLabel("No suites loaded. Click Refresh.")
    private val runButtons = mutableListOf<JButton>()

    // Compact icon-only creation buttons (folder+ for a suite, file+ for a test).
    private val addSuiteButton =
        compactButton(AllIcons.Actions.NewFolder, "Add a suite… (new suite_<name> folder)") { onAddSuite() }
    private val addTestButton =
        compactButton(AllIcons.Actions.AddFile, "Add a test… (new tst_<name> in the selected suite)") { onAddTest() }

    /** Whether each test's checkbox is ticked, keyed by test directory (default true). */
    private val checkedState = HashMap<Path, Boolean>()

    /** Tests queued by "Run Checked", executed one after another. */
    private val runQueue = ArrayDeque<SquishTest>()
    private var queueSuite: SquishSuite? = null

    /** Reports accumulated across a "Run Checked" batch, shown together like a suite run. */
    private val batchReports = mutableListOf<com.pysquish.report.SquishTestReport>()

    /** True until the first test of a batch launches, so the console is cleared only once. */
    private var batchFirst = false

    // Console level filter checkboxes.
    private val showLog = JCheckBox("Log", true)
    private val showPass = JCheckBox("Pass", true)
    private val showWarning = JCheckBox("Warning", true)
    private val showError = JCheckBox("Error/Fail", true)

    /** Last verdict per test, keyed by test directory. Session-only. */
    private val verdicts = HashMap<Path, SquishVerdict>()
    private var lastRunSuite: SquishSuite? = null

    @Volatile
    private var running = false

    init {
        Disposer.register(this, console)

        runner.stateListener = { isRunning ->
            running = isRunning
            updateControlsEnabled()
            if (!isRunning && queueSuite != null) {
                ApplicationManager.getApplication().invokeLater { runNextInQueue() }
            }
        }
        runner.reportListener = { report -> onReport(report) }

        suiteCombo.addActionListener { rebuildTestList() }
        suiteCombo.renderer = SuiteRenderer()

        toolbar = createToolbar().component
        setContent(createCenter())

        loadSuites()
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Refresh Suites", "Rescan the project for Squish suites", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = loadSuites()
            })
            add(object : AnAction("Run Whole Suite", "Run every test case in the selected suite", AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) = runSelectedSuite(debug = false)
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !running && selectedSuite() != null
                }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })
            add(object : AnAction("Run Checked", "Run all checked tests one after another", AllIcons.Actions.RunAll) {
                override fun actionPerformed(e: AnActionEvent) = runChecked()
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !running && selectedSuite()?.tests?.any { isChecked(it) } == true
                }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })
            add(object : AnAction("Stop", "Stop the running Squish test", AllIcons.Actions.Suspend) {
                override fun actionPerformed(e: AnActionEvent) {
                    runQueue.clear()
                    queueSuite = null
                    runner.stop()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = running
                }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction("PySquish Settings", "Configure Squish paths and options", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, SquishSettingsConfigurable::class.java)
                }
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("PySquishToolbar", group, false)
        toolbar.targetComponent = this
        return toolbar
    }

    private fun createCenter(): JPanel {
        val left = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)

            val top = JPanel(BorderLayout(6, 6))
            top.add(JBLabel("Suite:"), BorderLayout.WEST)
            top.add(suiteCombo, BorderLayout.CENTER)
            val addButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(addSuiteButton)
                add(addTestButton)
            }
            top.add(addButtons, BorderLayout.EAST)
            add(top, BorderLayout.NORTH)

            val scroll = JBScrollPane(testListPanel)
            scroll.border = JBUI.Borders.emptyTop(6)
            add(scroll, BorderLayout.CENTER)

            add(statusLabel.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.SOUTH)
            preferredSize = Dimension(320, preferredSize.height)
        }

        val tabs = JBTabbedPane().apply {
            addTab("Console", createConsolePanel())
            addTab("Report", reportPanel.component())
        }

        val splitter = JBSplitter(false, "pysquish.splitter", 0.35f)
        splitter.firstComponent = left
        splitter.secondComponent = tabs

        return JPanel(BorderLayout()).apply { add(splitter, BorderLayout.CENTER) }
    }

    /** Console tab: a level-filter bar on top of the streaming console. */
    private fun createConsolePanel(): JPanel {
        val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JBLabel("Show:"))
            listOf(showLog, showPass, showWarning, showError).forEach { cb ->
                cb.addActionListener { applyConsoleFilter() }
                add(cb)
            }
        }
        return JPanel(BorderLayout()).apply {
            add(filterBar, BorderLayout.NORTH)
            add(console.view.component, BorderLayout.CENTER)
        }
    }

    private fun applyConsoleFilter() {
        val levels = buildSet {
            if (showLog.isSelected) addAll(SquishConsole.LOG_GROUP)
            if (showPass.isSelected) addAll(SquishConsole.PASS_GROUP)
            if (showWarning.isSelected) addAll(SquishConsole.WARNING_GROUP)
            if (showError.isSelected) addAll(SquishConsole.ERROR_GROUP)
        }
        console.setVisibleLevels(levels)
    }

    /** Starts a run and remembers the suite so we can map report verdicts back. */
    private fun launch(
        suite: SquishSuite,
        test: com.pysquish.model.SquishTest?,
        debug: Boolean,
        clearConsole: Boolean = true,
    ) {
        lastRunSuite = suite
        runner.run(suite, test, debug, clearConsole)
    }

    private fun onReport(report: SquishRunReport?) {
        // During a "Run Checked" batch the queue is still active here (this fires
        // before runNextInQueue), so accumulate every test's report and show them
        // together, like a whole-suite run.
        val inBatch = queueSuite != null
        if (report != null) {
            val suite = lastRunSuite
            report.tests.forEach { tr ->
                val test = suite?.tests?.firstOrNull { matchesReportName(it, tr.name) }
                if (test != null) {
                    verdicts[test.directory] = tr.verdict
                    if (tr.verdict == SquishVerdict.FAIL) attachScreenshots(tr, test, suite)
                }
            }
        }
        val displayed = if (inBatch) {
            report?.tests?.let { batchReports.addAll(it) }
            SquishRunReport(batchReports.toList())
        } else {
            report
        }
        reportPanel.setReport(displayed)
        rebuildTestList()
    }

    /**
     * Attaches failure screenshots (`failed_*.png`) to a failed test. Prefers the
     * configured screenshots directory (kept in AppData, searched recursively);
     * when it is blank, falls back to the repo `failedImages/` folders.
     */
    private fun attachScreenshots(
        report: com.pysquish.report.SquishTestReport,
        test: com.pysquish.model.SquishTest,
        suite: SquishSuite,
    ) {
        val pattern = Regex("failed_.*\\.png", RegexOption.IGNORE_CASE)
        val configured = SquishSettings.state().screenshotsDir?.trim().orEmpty()

        val all = if (configured.isNotEmpty()) {
            walkImages(Path.of(configured), pattern)
        } else {
            listOf(test.directory, suite.directory)
                .map { it.resolve("failedImages") }
                .flatMap { walkImages(it, pattern) }
        }.distinct()

        // Prefer images whose path mentions this test; otherwise attach all found.
        val forTest = all.filter { it.toString().contains(test.name, ignoreCase = true) }
        val images = forTest.ifEmpty { all }
        if (images.isEmpty()) return

        val section = SquishReportNode.Section(title = "Screenshots")
        images.forEach { section.children.add(SquishReportNode.Image(it)) }
        report.root.children.add(section)
    }

    private fun walkImages(dir: Path, pattern: Regex): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && pattern.matches(it.fileName.toString()) }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    /** The report may name a test by full path or with different casing. */
    private fun matchesReportName(test: com.pysquish.model.SquishTest, reportName: String): Boolean {
        if (reportName.isBlank()) return false
        val leaf = reportName.trim().substringAfterLast('/').substringAfterLast('\\')
        return test.name.equals(reportName, ignoreCase = true) ||
            test.name.equals(leaf, ignoreCase = true) ||
            reportName.endsWith(test.name)
    }

    /** Always non-null so every row has a same-size icon and titles line up. */
    private fun statusIcon(test: com.pysquish.model.SquishTest): Icon = when (verdicts[test.directory]) {
        SquishVerdict.PASS -> AllIcons.RunConfigurations.TestPassed
        SquishVerdict.FAIL -> AllIcons.RunConfigurations.TestFailed
        else -> AllIcons.RunConfigurations.TestNotRan
    }

    private fun loadSuites(selectDir: Path? = null) {
        statusLabel.text = "Scanning for Squish suites…"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning for Squish suites", false) {
            private var found: List<SquishSuite> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                found = SquishProjectScanner.scan(project)
            }

            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater { populateSuites(found, selectDir) }
            }
        })
    }

    private fun populateSuites(suites: List<SquishSuite>, selectDir: Path? = null) {
        val previous = selectDir ?: selectedSuite()?.directory
        suiteCombo.removeAllItems()
        suites.forEach { suiteCombo.addItem(it) }

        when {
            suites.isEmpty() -> statusLabel.text = "No Squish suites found in this project."
            else -> {
                statusLabel.text = "${suites.size} suite(s) found."
                val keep = suites.firstOrNull { it.directory == previous }
                suiteCombo.selectedItem = keep ?: suites.first()
            }
        }
        rebuildTestList()
    }

    private fun rebuildTestList() {
        testListPanel.removeAll()
        runButtons.clear()

        val suite = selectedSuite()
        if (suite == null) {
            testListPanel.revalidate(); testListPanel.repaint(); return
        }

        if (suite.tests.isEmpty()) {
            testListPanel.add(JBLabel("This suite has no test cases (tst_*)."))
        }

        for (test in suite.tests) {
            val row = JPanel(BorderLayout(6, 0)).apply {
                border = JBUI.Borders.empty(0, 2)
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            }

            val check = JCheckBox().apply {
                isSelected = checkedState.getOrDefault(test.directory, true)
                toolTipText = "Include in 'Run Checked'"
                isBorderPainted = false
                margin = JBUI.insets(0)
                addActionListener { checkedState[test.directory] = isSelected }
            }
            row.add(check, BorderLayout.WEST)

            val name = JBLabel(test.name).apply {
                icon = statusIcon(test)
                toolTipText = when (verdicts[test.directory]) {
                    SquishVerdict.PASS -> "Last result: OK"
                    SquishVerdict.FAIL -> "Last result: KO"
                    else -> test.scriptFile?.toString() ?: "No test script found"
                }
                if (test.scriptFile == null) foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }
            row.add(name, BorderLayout.CENTER)

            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
            val runBtn = compactButton(AllIcons.Actions.Execute, "Run ${test.name}") { launch(suite, test, debug = false) }
            val debugBtn = compactButton(
                AllIcons.Actions.StartDebugger,
                "Debug ${test.name} (PyCharm debugger attached)",
            ) { launch(suite, test, debug = true) }
            runButtons.add(runBtn)
            runButtons.add(debugBtn)
            buttons.add(runBtn)
            buttons.add(debugBtn)
            row.add(buttons, BorderLayout.EAST)

            testListPanel.add(row)
        }
        // Push rows to the top.
        testListPanel.add(JPanel().apply { alignmentX = Component.LEFT_ALIGNMENT })

        updateControlsEnabled()
        testListPanel.revalidate()
        testListPanel.repaint()
    }

    private fun runSelectedSuite(debug: Boolean) {
        val suite = selectedSuite() ?: return
        launch(suite, null, debug)
    }

    private fun compactButton(icon: Icon, tip: String, action: () -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = tip
            margin = JBUI.insets(0)
            isFocusable = false
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            addActionListener { action() }
        }

    private fun isChecked(test: SquishTest): Boolean = checkedState.getOrDefault(test.directory, true)

    /** Queues every checked, runnable test in the selected suite and runs them in order. */
    private fun runChecked() {
        val suite = selectedSuite() ?: return
        if (running) return
        val checked = suite.tests.filter { isChecked(it) && it.scriptFile != null }
        if (checked.isEmpty()) return
        runQueue.clear()
        runQueue.addAll(checked)
        queueSuite = suite
        batchReports.clear()
        batchFirst = true
        runNextInQueue()
    }

    private fun runNextInQueue() {
        val suite = queueSuite ?: return
        val next = runQueue.removeFirstOrNull()
        if (next == null) {
            queueSuite = null
            return
        }
        // Clear the console only for the first test; the rest append to it.
        val clearConsole = batchFirst
        batchFirst = false
        launch(suite, next, debug = false, clearConsole = clearConsole)
    }

    private fun selectedSuite(): SquishSuite? = suiteCombo.selectedItem as? SquishSuite

    private fun updateControlsEnabled() {
        runButtons.forEach { it.isEnabled = !running }
        suiteCombo.isEnabled = !running
        addSuiteButton.isEnabled = !running
        addTestButton.isEnabled = !running && selectedSuite() != null
    }

    // --- Scaffolding new suites / tests --------------------------------------

    private fun onAddSuite() {
        if (running) return
        val dialog = NewSuiteDialog(project)
        if (!dialog.showAndGet()) return
        SquishScaffolder.createSuite(dialog.parentDir, dialog.suiteName, dialog.aut)
            .onFailure { showError("Could not create suite", it) }
            .onSuccess { suiteDir ->
                refreshInVfs(suiteDir)
                openInEditor(suiteDir.resolve("suite.conf"))
                loadSuites(suiteDir)
            }
    }

    private fun onAddTest() {
        if (running) return
        val suite = selectedSuite() ?: return
        val rawName = Messages.showInputDialog(
            project,
            "Test name (folder created as tst_<name>):",
            "New Test Case in ${suite.name}",
            Messages.getQuestionIcon(),
            "",
            object : InputValidator {
                override fun checkInput(input: String?): Boolean {
                    val name = input?.trim().orEmpty()
                    return SquishScaffolder.validateName(name) == null && !testExists(suite, name)
                }
                override fun canClose(input: String?): Boolean = checkInput(input)
            },
        )?.trim().takeUnless { it.isNullOrEmpty() } ?: return

        val template = SquishScaffolder.resolveTestTemplate(SquishSettings.state().testTemplatePath)
        SquishScaffolder.createTest(
            suiteDir = suite.directory,
            rawName = rawName,
            templateText = template,
            suiteName = suite.name,
            aut = readAut(suite),
            language = suite.language ?: "Python",
        )
            .onFailure { showError("Could not create test", it) }
            .onSuccess { script ->
                refreshInVfs(suite.directory)
                openInEditor(script)
                loadSuites(suite.directory)
            }
    }

    private fun testExists(suite: SquishSuite, rawName: String): Boolean {
        if (rawName.isEmpty()) return false
        return Files.exists(suite.directory.resolve(SquishScaffolder.testDirName(rawName)))
    }

    /** Reads the `AUT=` value from a suite's `suite.conf` (blank if absent). */
    private fun readAut(suite: SquishSuite): String = runCatching {
        Files.readAllLines(suite.confFile).firstOrNull { line ->
            val sep = line.indexOf('=')
            sep > 0 && line.substring(0, sep).trim().equals("AUT", ignoreCase = true)
        }?.substringAfter('=')?.trim().orEmpty()
    }.getOrDefault("")

    private fun showError(title: String, t: Throwable) {
        Messages.showErrorDialog(project, t.message ?: t.toString(), title)
    }

    /**
     * Refreshes the VFS for a freshly created path (and its ancestor tree) and
     * returns its [VirtualFile]. The synchronous refresh fires VFS events, so on
     * the EDT it must run inside a write action.
     */
    private fun refreshInVfs(path: Path): VirtualFile? =
        WriteAction.compute<VirtualFile?, RuntimeException> {
            val lfs = LocalFileSystem.getInstance()
            val vf = lfs.refreshAndFindFileByNioFile(path)
            lfs.refreshAndFindFileByNioFile(path)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
            vf
        }

    private fun openInEditor(path: Path) {
        val vf = refreshInVfs(path) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    override fun dispose() {
        runner.stop()
    }

    private class SuiteRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is SquishSuite && c is javax.swing.JLabel) {
                c.text = value.name
                c.toolTipText = value.directory.toString()
                c.horizontalAlignment = SwingConstants.LEFT
            } else if (value == null && c is javax.swing.JLabel) {
                c.text = "— select a suite —"
            }
            return c
        }
    }
}
