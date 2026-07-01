package com.pysquish.toolwindow

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.pysquish.model.SquishScaffolder
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Dialog for **+ Add a suite…**: collects the suite name, the AUT (application
 * under test, optional) and the parent directory the `suite_<name>` folder is
 * created in.
 */
class NewSuiteDialog(private val project: Project) : DialogWrapper(project) {

    private val nameField = JBTextField()
    private val autField = JBTextField()
    private val locationField = TextFieldWithBrowseButton(JBTextField()).apply {
        text = project.basePath ?: ""
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Parent Folder For The New Suite"),
        )
    }

    val suiteName: String get() = nameField.text.trim()
    val aut: String get() = autField.text.trim()
    val parentDir: Path get() = Path.of(locationField.text.trim())

    init {
        title = "New Squish Suite"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Suite name:") {
            cell(nameField).align(AlignX.FILL)
                .comment("Folder created as <code>suite_&lt;name&gt;</code> (prefix added if missing).")
        }.layout(com.intellij.ui.dsl.builder.RowLayout.LABEL_ALIGNED)
        row("AUT (app under test):") {
            cell(autField).align(AlignX.FILL)
                .comment("Optional. Written as <code>AUT=</code> in suite.conf; can be left blank.")
        }
        row("Location:") {
            cell(locationField).align(AlignX.FILL)
                .comment("Parent folder the suite directory is created in.")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        SquishScaffolder.validateName(suiteName)?.let { return ValidationInfo(it, nameField) }
        val location = locationField.text.trim()
        if (location.isEmpty()) return ValidationInfo("Choose a location.", locationField)
        val parent = runCatching { Path.of(location) }.getOrNull()
            ?: return ValidationInfo("Invalid location path.", locationField)
        if (!Files.isDirectory(parent)) return ValidationInfo("Location is not an existing folder.", locationField)
        val target = parent.resolve(SquishScaffolder.suiteDirName(suiteName))
        if (Files.exists(target)) {
            return ValidationInfo("A folder named \"${target.fileName}\" already exists here.", nameField)
        }
        return null
    }
}
