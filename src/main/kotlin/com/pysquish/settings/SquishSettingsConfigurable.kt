package com.pysquish.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI

/**
 * Settings page shown under **Settings | Tools | PySquish**.
 */
class SquishSettingsConfigurable : BoundConfigurable("PySquish") {

    private val state get() = SquishSettings.state()

    override fun createPanel(): DialogPanel = panel {
        group("Squish Installation") {
            row("squishrunner executable:") {
                cell(executableChooser())
                    .bindText({ state.squishRunnerPath ?: "" }, { state.squishRunnerPath = it })
                    .comment("Path to squishrunner (Windows: squishrunner.exe). Required.")
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row("squishserver executable:") {
                cell(executableChooser())
                    .bindText({ state.squishServerPath ?: "" }, { state.squishServerPath = it })
                    .comment("Optional. Only used when 'Start a local squishserver' is enabled.")
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
        }

        group("Execution") {
            row {
                checkBox("Start a local squishserver before running tests")
                    .bindSelected({ state.startServer }, { state.startServer = it })
            }
            row {
                checkBox("Connect squishrunner to a running squishserver (--host/--port)")
                    .bindSelected({ state.connectToServer }, { state.connectToServer = it })
                    .comment(
                        "Passes the host/port below to squishrunner (before --testsuite). " +
                            "Implied when a local squishserver is started.",
                    )
            }
            row("Server host:") {
                textField()
                    .bindText({ state.serverHost ?: "" }, { state.serverHost = it })
                    .comment("Optional. Leave blank to omit --host (squishrunner uses its default).")
            }
            row("Server port:") {
                intTextField(1024..65535)
                    .bindIntText({ state.serverPort }, { state.serverPort = it })
                    .comment("squishserver port passed to squishrunner as --port.")
            }
            row("Extra squishrunner args:") {
                textField()
                    .bindText({ state.extraRunnerArgs ?: "" }, { state.extraRunnerArgs = it })
                    .comment("Appended to every run, e.g. <code>--reportgen stdout --tags smoke</code>.")
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row("Report generator (XML):") {
                textField()
                    .bindText({ state.reportGenerator ?: "" }, { state.reportGenerator = it })
                    .comment(
                        "Squish XML generator for the Report tab / verdicts, e.g. <code>xml3.4</code>. " +
                            "Must be one your squishrunner supports (a native XML format, not xmljunit).",
                    )
            }
        }

        group("Python Debugger (remote attach)") {
            row("Debug host:") {
                textField()
                    .bindText({ state.debugHost ?: "" }, { state.debugHost = it })
                    .comment("Host the PyCharm debug server listens on. Use the IDE machine's address.")
            }
            row("Debug port:") {
                intTextField(1024..65535)
                    .bindIntText({ state.debugPort }, { state.debugPort = it })
            }
            row("pydevd_pycharm path:") {
                cell(directoryChooser())
                    .bindText({ state.pydevdPath ?: "" }, { state.pydevdPath = it })
                    .comment(
                        "Directory containing the <code>pydevd_pycharm</code> module. " +
                            "Leave blank to auto-detect from bundled PyCharm helpers.",
                    )
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
        }
    }

    private fun executableChooser(): TextFieldWithBrowseButton {
        val field = TextFieldWithBrowseButton(JBTextField())
        field.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Select Squish Executable"),
        )
        field.preferredSize = JBUI.size(420, field.preferredSize.height)
        return field
    }

    private fun directoryChooser(): TextFieldWithBrowseButton {
        val field = TextFieldWithBrowseButton(JBTextField())
        field.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select pydevd_pycharm Directory"),
        )
        field.preferredSize = JBUI.size(420, field.preferredSize.height)
        return field
    }
}
