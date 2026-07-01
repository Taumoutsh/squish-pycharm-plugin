package com.pysquish.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.pysquish.report.SquishLogLevel
import com.pysquish.report.SquishReportNode
import com.pysquish.report.SquishRunReport
import com.pysquish.report.SquishTestReport
import com.pysquish.report.SquishVerdict
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The "Report" tab: a foldable tree of the parsed Squish report. Sections are
 * collapsible layers; entries are colored/iconed by level. Sections (and tests)
 * that contain a failure are auto-expanded; everything else starts collapsed.
 */
class SquishReportPanel : JPanel(BorderLayout()) {

    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode("Report"))).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = ReportCellRenderer()
    }
    private val emptyLabel = JBLabel("No report yet. Run a test to populate this tab.").apply {
        border = JBUI.Borders.empty(8)
    }

    init {
        add(emptyLabel, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    /** Rebuilds the tree from [report]; pass null to clear. */
    fun setReport(report: SquishRunReport?) {
        val root = DefaultMutableTreeNode("Report")
        if (report == null || report.tests.isEmpty()) {
            emptyLabel.isVisible = true
            tree.model = DefaultTreeModel(root)
            return
        }
        emptyLabel.isVisible = false
        for (test in report.tests) {
            val testNode = DefaultMutableTreeNode(test)
            addChildren(testNode, test.root)
            root.add(testNode)
        }
        tree.model = DefaultTreeModel(root)
        expandFailures(root)
    }

    private fun addChildren(parent: DefaultMutableTreeNode, section: SquishReportNode.Section) {
        for (child in section.children) {
            when (child) {
                is SquishReportNode.Section -> {
                    val node = DefaultMutableTreeNode(child)
                    addChildren(node, child)
                    parent.add(node)
                }
                is SquishReportNode.Entry -> parent.add(DefaultMutableTreeNode(child))
            }
        }
    }

    /** Expands tests/sections that contain a failure; leaves the rest collapsed. */
    private fun expandFailures(root: DefaultMutableTreeNode) {
        for (i in 0 until root.childCount) {
            val testNode = root.getChildAt(i) as DefaultMutableTreeNode
            val test = testNode.userObject as? SquishTestReport ?: continue
            if (test.verdict == SquishVerdict.FAIL) {
                tree.expandPath(TreePath(testNode.path))
                expandFailingSections(testNode)
            }
        }
    }

    private fun expandFailingSections(node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val section = child.userObject as? SquishReportNode.Section ?: continue
            if (section.containsFailure) {
                tree.expandPath(TreePath(child.path))
                expandFailingSections(child)
            }
        }
    }

    fun component(): JComponent = this

    private class ReportCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: javax.swing.JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val userObject = (value as? DefaultMutableTreeNode)?.userObject
            when (userObject) {
                is SquishTestReport -> {
                    icon = when (userObject.verdict) {
                        SquishVerdict.PASS -> AllIcons.RunConfigurations.TestPassed
                        SquishVerdict.FAIL -> AllIcons.RunConfigurations.TestFailed
                        SquishVerdict.UNKNOWN -> AllIcons.RunConfigurations.TestNotRan
                    }
                    append(userObject.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is SquishReportNode.Section -> {
                    icon = AllIcons.Nodes.Folder
                    append(userObject.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (userObject.containsFailure) {
                        append("  (failed)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                }
                is SquishReportNode.Entry -> {
                    icon = iconFor(userObject.level)
                    append("${userObject.level}: ", attributesFor(userObject.level))
                    append(userObject.message, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                else -> append(userObject?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        private fun iconFor(level: SquishLogLevel) = when (level) {
            SquishLogLevel.PASS -> AllIcons.RunConfigurations.TestPassed
            SquishLogLevel.FAIL, SquishLogLevel.FATAL -> AllIcons.RunConfigurations.TestFailed
            SquishLogLevel.ERROR -> AllIcons.General.Error
            SquishLogLevel.WARNING -> AllIcons.General.Warning
            SquishLogLevel.INFO -> AllIcons.General.Information
            SquishLogLevel.LOG, SquishLogLevel.UNKNOWN -> AllIcons.General.Note
        }

        private fun attributesFor(level: SquishLogLevel): SimpleTextAttributes = when (level) {
            SquishLogLevel.PASS ->
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x1A8F3C, 0x5FAD5F))
            SquishLogLevel.FAIL, SquishLogLevel.ERROR, SquishLogLevel.FATAL ->
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xC0392B, 0xE06C5B))
            SquishLogLevel.WARNING ->
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xB8860B, 0xD9A441))
            SquishLogLevel.INFO ->
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x2A6FDB, 0x6897BB))
            SquishLogLevel.LOG, SquishLogLevel.UNKNOWN ->
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x7A7A7A, 0x999999))
        }
    }
}
