package com.pysquish.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
import com.pysquish.execution.SquishScriptLocator
import com.pysquish.report.SquishXmlReportParser.TRACEBACK_MARKER
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The "Report" tab: a foldable tree of the parsed Squish report. Sections are
 * collapsible layers; entries are colored/iconed by level. On failure the tree
 * unfolds down to each error and scrolls to the first. Tracebacks render as a
 * monospaced foldable block, failure screenshots as openable image nodes, and
 * any node can be copied (Ctrl/Cmd+C or right-click).
 */
class SquishReportPanel(private val project: Project) : JPanel(BorderLayout()) {

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
        installCopyAction()
        installMouseActions()
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
        unfoldToFailures(root)
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
                is SquishReportNode.Image -> parent.add(DefaultMutableTreeNode(child))
            }
        }
    }

    /** Expands the full path to every failure (+ traceback), scrolls to the first. */
    private fun unfoldToFailures(root: DefaultMutableTreeNode) {
        var firstFailure: DefaultMutableTreeNode? = null

        fun visit(node: DefaultMutableTreeNode) {
            val obj = node.userObject
            val isFailureEntry = obj is SquishReportNode.Entry && obj.level.isFailure
            val isTraceback = obj is SquishReportNode.Section && obj.title == TRACEBACK_MARKER
            if (isFailureEntry || isTraceback) {
                expandAncestors(node)
                if (isFailureEntry && firstFailure == null) firstFailure = node
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i) as DefaultMutableTreeNode)
        }
        for (i in 0 until root.childCount) visit(root.getChildAt(i) as DefaultMutableTreeNode)

        firstFailure?.let {
            val path = TreePath(it.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    /** Expands each ancestor from the root down so [node] becomes visible. */
    private fun expandAncestors(node: DefaultMutableTreeNode) {
        val paths = ArrayList<TreePath>()
        var current: TreePath? = TreePath(node.path)
        while (current != null) {
            paths.add(0, current)
            current = current.parentPath
        }
        paths.forEach { tree.expandPath(it) }
    }

    // --- copy --------------------------------------------------------------

    private fun installCopyAction() {
        val copyKey = KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        tree.inputMap.put(copyKey, "pysquish-copy")
        tree.actionMap.put("pysquish-copy", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = copySelection()
        })
    }

    private fun installMouseActions() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !openSelectedImage()) openSelectedLocation()
            }
        })
    }

    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = tree.getClosestRowForLocation(e.x, e.y)
        val selectedRows = tree.selectionRows
        if (row >= 0 && (selectedRows == null || row !in selectedRows)) tree.setSelectionRow(row)
        JPopupMenu().apply {
            add(JMenuItem("Copy").apply { addActionListener { copySelection() } })
            selectedImagePath()?.let {
                add(JMenuItem("Open Image").apply { addActionListener { openSelectedImage() } })
            }
        }.show(tree, e.x, e.y)
    }

    private fun copySelection() {
        val text = tree.selectionPaths.orEmpty()
            .mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode }
            .joinToString("\n") { copyText(it) }
        if (text.isNotEmpty()) CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun copyText(node: DefaultMutableTreeNode): String = when (val obj = node.userObject) {
        is SquishTestReport -> obj.name
        is SquishReportNode.Entry ->
            if (obj.level == SquishLogLevel.TRACEBACK) obj.message else "${obj.level}: ${obj.message}"
        is SquishReportNode.Image -> obj.path.toString()
        is SquishReportNode.Section -> buildString {
            append(obj.title)
            for (i in 0 until node.childCount) {
                append('\n').append(copyText(node.getChildAt(i) as DefaultMutableTreeNode))
            }
        }
        else -> obj?.toString().orEmpty()
    }

    // --- images ------------------------------------------------------------

    private fun selectedImagePath(): java.nio.file.Path? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? SquishReportNode.Image)?.path

    private fun openSelectedImage(): Boolean {
        val path = selectedImagePath() ?: return false
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return false
        return runCatching { OpenFileDescriptor(project, vf).navigate(true); true }.getOrDefault(false)
    }

    /** Opens the repository script referenced by the selected entry's location. */
    private fun openSelectedLocation(): Boolean {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return false
        val entry = node.userObject as? SquishReportNode.Entry ?: return false
        val loc = SquishScriptLocator.parse(entry.detail) ?: SquishScriptLocator.parse(entry.message) ?: return false
        val vf = SquishScriptLocator.findInProject(project, loc.fileName) ?: return false
        return runCatching {
            OpenFileDescriptor(project, vf, (loc.line - 1).coerceAtLeast(0), 0).navigate(true); true
        }.getOrDefault(false)
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
            when (val obj = (value as? DefaultMutableTreeNode)?.userObject) {
                is SquishTestReport -> {
                    icon = when (obj.verdict) {
                        SquishVerdict.PASS -> AllIcons.RunConfigurations.TestPassed
                        SquishVerdict.FAIL -> AllIcons.RunConfigurations.TestFailed
                        SquishVerdict.UNKNOWN -> AllIcons.RunConfigurations.TestNotRan
                    }
                    append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is SquishReportNode.Section -> {
                    val isTraceback = obj.title == TRACEBACK_MARKER
                    icon = if (isTraceback) AllIcons.General.Error else AllIcons.Nodes.Folder
                    append(obj.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (!isTraceback && obj.containsFailure) {
                        append("  (failed)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                }
                is SquishReportNode.Entry -> {
                    if (obj.level == SquishLogLevel.TRACEBACK) {
                        append(obj.message, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    } else {
                        icon = iconFor(obj.level)
                        append("${obj.level}: ", attributesFor(obj.level))
                        append(obj.message, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        obj.detail?.let { detail ->
                            // A resolvable script location renders as a link (double-click to open).
                            val attr = if (SquishScriptLocator.parse(detail) != null) {
                                SimpleTextAttributes.LINK_ATTRIBUTES
                            } else {
                                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
                            }
                            append("  ($detail)", attr)
                        }
                    }
                }
                is SquishReportNode.Image -> {
                    icon = AllIcons.FileTypes.Image
                    append(obj.path.fileName.toString(), SimpleTextAttributes.LINK_ATTRIBUTES)
                    append("  (double-click to open)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                else -> append(obj?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        private fun iconFor(level: SquishLogLevel) = when (level) {
            SquishLogLevel.PASS -> AllIcons.RunConfigurations.TestPassed
            SquishLogLevel.FAIL, SquishLogLevel.FATAL -> AllIcons.RunConfigurations.TestFailed
            SquishLogLevel.ERROR -> AllIcons.General.Error
            SquishLogLevel.WARNING -> AllIcons.General.Warning
            SquishLogLevel.INFO -> AllIcons.General.Information
            SquishLogLevel.LOG, SquishLogLevel.TRACEBACK, SquishLogLevel.UNKNOWN -> AllIcons.General.Note
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
            SquishLogLevel.LOG, SquishLogLevel.TRACEBACK, SquishLogLevel.UNKNOWN ->
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x7A7A7A, 0x999999))
        }
    }
}
