package com.pysquish.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-wide persisted configuration for PySquish.
 *
 * Stored in `pysquish.xml` in the IDE config directory so the same Squish
 * installation paths are reused across projects.
 */
@State(
    name = "PySquishSettings",
    storages = [Storage("pysquish.xml")],
)
class SquishSettings : PersistentStateComponent<SquishSettings.State> {

    class State : BaseState() {
        /** Absolute path to the `squishrunner` (or `squishrunner.exe`) executable. */
        var squishRunnerPath by string("")

        /** Optional path to `squishserver`. Only used when [startServer] is enabled. */
        var squishServerPath by string("")

        /** When true, the plugin starts a local squishserver before running tests. */
        var startServer by property(false)

        /**
         * When true, `--host`/`--port` are passed to squishrunner so it connects
         * to an already-running squishserver. Starting a local server
         * ([startServer]) implies this too.
         */
        var connectToServer by property(false)

        /** Host the runner connects to (relevant for client/server mode). */
        var serverHost by string("127.0.0.1")

        /** Port squishserver listens on / squishrunner connects to. */
        var serverPort by property(4322)

        /**
         * Extra command-line arguments appended verbatim to every squishrunner
         * invocation (space separated, simple quoting supported).
         */
        var extraRunnerArgs by string("--reportgen stdout")

        /**
         * Squish XML report generator used to feed the Report tab and per-test
         * verdicts (e.g. `xml3.4`). Must be one your `squishrunner` supports;
         * a Squish-native XML format (not `xmljunit`) is required for sections.
         */
        var reportGenerator by string("xml3.4")

        /**
         * Directory where Squish stores failure screenshots (e.g. an AppData
         * results path). When set, the Report tab looks here for `failed_*.png`
         * instead of the repository's `failedImages/` folders, so screenshots
         * stay in AppData and are not copied into the repo. Blank = old behavior.
         */
        var screenshotsDir by string("")

        /** Host PyCharm's Python debug server listens on for remote attach. */
        var debugHost by string("127.0.0.1")

        /** Port PyCharm's Python debug server listens on for remote attach. */
        var debugPort by property(5678)

        /**
         * Path to the directory containing `pydevd_pycharm` (the PyCharm
         * "Python Debug Server" egg/wheel). Added to PYTHONPATH so the Squish
         * interpreter can import it. If blank, PySquish tries to auto-detect it
         * from the bundled PyCharm helpers.
         */
        var pydevdPath by string("")
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): SquishSettings =
            ApplicationManager.getApplication().getService(SquishSettings::class.java)

        fun state(): State = getInstance().state
    }
}
