package com.pysquish.toolwindow

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBTabbedPane
import com.pysquish.execution.SquishTestRunner
import com.pysquish.model.SquishProjectScanner
import com.pysquish.model.SquishSuite
import com.pysquish.report.SquishReportNode
import com.pysquish.report.SquishRunReport
import com.pysquish.report.SquishVerdict
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
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The PySquish tool window: a suite combo box, a per-test run/debug button list,
 * and a console that streams squishrunner output.
 */
class SquishToolWindowPanel(private val project: Project) :
    SimpleToolWindowPanel(false, true), com.intellij.openapi.Disposable {

    private val console: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    private val runner = SquishTestRunner(project, console)

    private val reportPanel = SquishReportPanel(project)

    private val suiteCombo = JComboBox<SquishSuite>()
    private val testListPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val statusLabel = JBLabel("No suites loaded. Click Refresh.")
    private val runButtons = mutableListOf<JButton>()

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
            add(object : AnAction("Stop", "Stop the running Squish test", AllIcons.Actions.Suspend) {
                override fun actionPerformed(e: AnActionEvent) = runner.stop()
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
            add(top, BorderLayout.NORTH)

            val scroll = JBScrollPane(testListPanel)
            scroll.border = JBUI.Borders.emptyTop(6)
            add(scroll, BorderLayout.CENTER)

            add(statusLabel, BorderLayout.SOUTH)
            preferredSize = Dimension(320, preferredSize.height)
        }

        val tabs = JBTabbedPane().apply {
            addTab("Console", console.component)
            addTab("Report", reportPanel.component())
        }

        val splitter = JBSplitter(false, "pysquish.splitter", 0.35f)
        splitter.firstComponent = left
        splitter.secondComponent = tabs

        return JPanel(BorderLayout()).apply { add(splitter, BorderLayout.CENTER) }
    }

    /** Starts a run and remembers the suite so we can map report verdicts back. */
    private fun launch(suite: SquishSuite, test: com.pysquish.model.SquishTest?, debug: Boolean) {
        lastRunSuite = suite
        runner.run(suite, test, debug)
    }

    private fun onReport(report: SquishRunReport?) {
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
        reportPanel.setReport(report)
        rebuildTestList()
    }

    /** Adds any `failedImages/failed_*.png` (under the test or suite dir) to a failed test. */
    private fun attachScreenshots(
        report: com.pysquish.report.SquishTestReport,
        test: com.pysquish.model.SquishTest,
        suite: SquishSuite,
    ) {
        val pattern = Regex("failed_.*\\.png", RegexOption.IGNORE_CASE)
        val images = listOf(test.directory, suite.directory)
            .map { it.resolve("failedImages") }
            .filter { Files.isDirectory(it) }
            .flatMap { dir ->
                runCatching {
                    Files.list(dir).use { stream ->
                        stream.filter { Files.isRegularFile(it) && pattern.matches(it.fileName.toString()) }
                            .sorted()
                            .toList()
                    }
                }.getOrDefault(emptyList())
            }
            .distinct()
        if (images.isEmpty()) return
        val section = SquishReportNode.Section(title = "Screenshots")
        images.forEach { section.children.add(SquishReportNode.Image(it)) }
        report.root.children.add(section)
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

    private fun loadSuites() {
        statusLabel.text = "Scanning for Squish suites…"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning for Squish suites", false) {
            private var found: List<SquishSuite> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                found = SquishProjectScanner.scan(project)
            }

            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater { populateSuites(found) }
            }
        })
    }

    private fun populateSuites(suites: List<SquishSuite>) {
        val previous = selectedSuite()?.directory
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
            val row = JPanel(BorderLayout(8, 0)).apply {
                border = JBUI.Borders.empty(3, 2)
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(34))
            }

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

            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            val runBtn = JButton(AllIcons.Actions.Execute).apply {
                toolTipText = "Run ${test.name}"
                margin = JBUI.insets(2)
                addActionListener { launch(suite, test, debug = false) }
            }
            val debugBtn = JButton(AllIcons.Actions.StartDebugger).apply {
                toolTipText = "Debug ${test.name} (PyCharm debugger attached)"
                margin = JBUI.insets(2)
                addActionListener { launch(suite, test, debug = true) }
            }
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

    private fun selectedSuite(): SquishSuite? = suiteCombo.selectedItem as? SquishSuite

    private fun updateControlsEnabled() {
        runButtons.forEach { it.isEnabled = !running }
        suiteCombo.isEnabled = !running
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
