# PySquish — PyCharm plugin for running Squish tests

PySquish is an IntelliJ Platform plugin (PyCharm 2025.1+) that discovers
[Squish](https://www.qt.io/product/quality-assurance/squish) test suites in the
opened project, lets you pick a suite and run any test case from a tool window,
streams `squishrunner` output into a log console, and can attach the PyCharm
Python debugger to the running test so breakpoints are honored.

## Development workflow

Push changes **directly to `main`** — that is the working convention for this
repo. Avoid throwaway feature branches: the assistant's git relay can create and
update branches but **cannot delete remote branches or push tags**, so any branch
it creates lingers and release tags must be pushed from a developer machine.

## Status

v0.3.0 — adds **suite / test scaffolding**: a **+ Add a suite…** button (next to
the suite combo) and a **+ Add a test…** button create a new `suite_<name>`
(with a generated `suite.conf`) or a new `tst_<name>` test case, the latter
registered in `suite.conf`'s `TEST_CASES`. `suite.conf` comes from a built-in
template; the test **script** is rendered from a user-editable **Mustache**
template (bundled default at
[templates/test.py.mustache](src/main/resources/templates/test.py.mustache),
overridable in Settings). See *Creating suites and tests* below.

v0.2.0 — adds per-test result badges, a colored console, a structured **Report**
tab (parsed from the Squish `xml3.4` report), and automatic teardown of the
Python Debug Server when a run ends. Builds with `./gradlew buildPlugin`. The
console coloring and XML report parsing are **format-driven** — the level
keywords and the `xml3.4` element handling are isolated (see
[report/](src/main/kotlin/com/pysquish/report/) and
[SquishConsolePrinter](src/main/kotlin/com/pysquish/execution/SquishConsolePrinter.kt))
and should be verified against real `squishrunner` output for your Squish build.
The debugger auto-start and `pydevd` auto-attach remain best-effort — see
*Debugging* below.

> **Debugging needs PyCharm Professional.** The remote *Python Debug Server*
> (pydevd attach) does not exist in Community edition, so breakpoints can only be
> honored on Professional. **Run** works on any edition. For real Squish, attach
> from inside the test (`import pysquish_debug; pysquish_debug.attach()` at the
> top of `main()`) since Squish can reset tracing after interpreter startup.

## Requirements

- PyCharm 2025.1 or newer (`sinceBuild = 251`, no upper bound). Works in any IDE
  with the Python plugin (depends on `com.intellij.modules.python`).
  **Debugging (breakpoints) requires PyCharm Professional.**
- JDK 21 (the platform's runtime for 2025.x).
- A Squish installation providing `squishrunner` (`squishrunner.exe` on Windows).

## Build & run

The installable artifact is a **zip** in `build/distributions/` (IntelliJ plugins
ship as a zip — install it in PyCharm via
**Settings | Plugins | ⚙ | Install Plugin from Disk…**, then restart).

### Option A — Docker (no local JDK/Gradle/IntelliJ needed)

The IntelliJ Platform Gradle plugin downloads the PyCharm SDK itself, so the
container only needs JDK 21. This writes `dist/pysquish-<version>.zip` on the host:

```bash
./docker-build.sh
# equivalently:
DOCKER_BUILDKIT=1 docker build --target artifact --output type=local,dest=dist .
```

First run downloads the SDK (~hundreds of MB); BuildKit cache mounts make repeat
builds fast. See [Dockerfile](Dockerfile).

### Option B — local convenience script

```bash
./build-plugin.sh            # clean build; finds JDK 21 via /usr/libexec/java_home on macOS
./build-plugin.sh --fast     # incremental
```
(`build-plugin.bat` for Windows.) Output zip is copied to `dist/`.

### Option C — Gradle directly (needs JDK 21)

```bash
./gradlew buildPlugin       # produces build/distributions/pysquish-*.zip
./gradlew runIde            # launches a sandbox PyCharm with the plugin loaded
./gradlew test              # unit tests
./gradlew verifyPlugin      # plugin-verifier compatibility check
```

The Gradle wrapper jar is committed, so `./gradlew` works without a local Gradle.
Build config lives in [build.gradle.kts](build.gradle.kts) and
[gradle.properties](gradle.properties) (platform type/version, sinceBuild,
bundled plugins).

## Configuration

**Settings | Tools | PySquish** (backed by
[SquishSettings](src/main/kotlin/com/pysquish/settings/SquishSettings.kt),
persisted application-wide in `pysquish.xml`):

- **squishrunner executable** — required; cross-platform (point at `.exe` on
  Windows).
- **squishserver executable** + **Start a local squishserver** — optional. When
  enabled, PySquish starts a `squishserver` before each run and passes
  `--host/--port` to the runner. Default is *runner-only* (off).
- **Connect squishrunner to a running squishserver** — optional. Passes
  `--host/--port` to the runner (positioned before `--testsuite`) without
  starting a local server, e.g. to reach a squishserver you started yourself.
  Implied when *Start a local squishserver* is on.
- **Server host / port** — used when starting or connecting to a squishserver.
  A blank host omits `--host` so squishrunner uses its default; only `--port`
  is passed.
- **Extra squishrunner args** — appended verbatim to every run (default
  `--reportgen stdout` so results land in the console).
- **Report generator (XML)** — the Squish XML generator PySquish adds for the
  Report tab / verdicts (default `xml3.4`). Must be one your `squishrunner`
  supports; use a native XML format (not `xmljunit`) so sections are preserved.
- **Screenshots directory** — where Squish writes failure screenshots (e.g. an
  AppData results path). When set, the Report tab looks there (searched
  recursively for `failed_*.png`) so screenshots stay in AppData instead of the
  repo. Blank = look in the suite/test `failedImages/` folders.
- **Test script template (.mustache)** — the Mustache template used by
  **+ Add a test…**. Blank = the bundled
  [templates/test.py.mustache](src/main/resources/templates/test.py.mustache).
  Placeholders: `{{testName}}`, `{{suiteName}}`, `{{aut}}`, `{{language}}`,
  `{{date}}`, and the `{{#hasAut}}…{{/hasAut}}` section (true when the suite has
  an AUT). See *Creating suites and tests*.
- **Debug host / port** and **pydevd_pycharm path** — see *Debugging*.

Console output is always decoded as **UTF-8** (and `PYTHONIOENCODING=utf-8` is
set) so accented characters (é, è, …) render correctly.

## Usage

1. Open a project containing Squish suites.
2. Open the **PySquish** tool window (bottom).
3. It scans for suites automatically; the toolbar **Refresh** rescans.
4. Pick a suite in the combo box. Each test row has a **checkbox** (ticked by
   default) at the start, the test name with its last verdict — green ✓ (OK) or
   red ✗ (KO), grey when not yet run (so titles stay aligned), session-only — and
   compact **Run** / **Debug** buttons. **Double-click a test** to open its script
   in the editor. A **Select all / Unselect all** button above the list toggles
   every checkbox. The toolbar has **Run Whole Suite**, **Run Checked** (runs
   every ticked test one after another), **Stop**, and a **Settings** shortcut.
5. The right side has two tabs:
   - **Console** — live `squishrunner` output, colored by level (`PASS` green,
     `FAIL`/`ERROR` red, `WARNING` orange, `INFO` blue, `LOG` grey). A **Show:**
     filter bar (Log / Pass / Warning / Error·Fail) toggles which levels are visible.
     Lines are tagged `P<phase>-S<step>` (between the timestamp and the log type):
     a log line containing `Start Section: Phase X, Step X` sets the current phase
     and step, and every following line carries the latest values until the next
     such marker (nothing before the first). Script **locations are clickable** and
     open the matching script in the project (resolved by file name, so the repo
     copy — not the absolute log path — is opened).
   - **Report** — a foldable tree parsed from the Squish `xml3.4` report
     (`<test type="section">` become collapsible layers, nested to any depth;
     entries are iconed/colored by type). On failure it **unfolds down to each
     error** and scrolls to the first. A trailing Python **traceback** is shown
     as its own foldable block (the exception line stays red). Entry **locations
     are clickable links** (double-click opens the repo script). Any node is
     **copyable** (Ctrl/Cmd+C or right-click). Failure **screenshots**
     (`failed_*.png`, from the configured *Screenshots directory* or the
     `failedImages/` folders) appear as image nodes you can double-click to open.

## Creating suites and tests

Two buttons in the tool window scaffold new Squish artifacts
([SquishScaffolder](src/main/kotlin/com/pysquish/model/SquishScaffolder.kt)):

- **+ Add a suite…** (right of the suite combo) opens
  [NewSuiteDialog](src/main/kotlin/com/pysquish/toolwindow/NewSuiteDialog.kt):
  a **name**, an optional **AUT**, and a **location** (folder chooser, defaults
  to the project base). It creates `<location>/suite_<name>/` with a generated
  `suite.conf` (`AUT`, `LANGUAGE=Python`, `OBJECTMAPSTYLE=script`, `VERSION=3`,
  `WRAPPERS=Qt`). The `suite_` prefix is added if you omit it.
- **+ Add a test…** (bottom-left, enabled once a suite is selected) prompts for
  a **name** and creates `tst_<name>/` with a rendered script, then registers
  `tst_<name>` in the suite's `suite.conf` `TEST_CASES` (creating that line, in
  alphabetical key order, if it was absent). The `tst_` prefix is added if you
  omit it.

`suite.conf` is produced from a built-in string template; the test **script** is
rendered with **Mustache** ([jmustache](https://github.com/samskivert/jmustache),
bundled in the plugin). The template is the *Test script template* setting or,
when blank, the bundled
[templates/test.py.mustache](src/main/resources/templates/test.py.mustache).
The template context is `{{testName}}` (e.g. `tst_login`), `{{suiteName}}`,
`{{aut}}` (defaulted from the suite's `AUT`), `{{language}}`, `{{date}}`, and the
boolean section `{{#hasAut}}…{{/hasAut}}`. After creation PySquish refreshes the
VFS, opens the new file in the editor, and reselects the suite. Names are
restricted to `[A-Za-z0-9_-]` (no spaces); existing folders are never
overwritten. The pure helpers (name prefixing, `suite.conf` building,
`TEST_CASES` editing, rendering) are unit-tested in
[SquishScaffolderTest](src/test/kotlin/com/pysquish/model/SquishScaffolderTest.kt).

## How discovery works

[SquishProjectScanner](src/main/kotlin/com/pysquish/model/SquishProjectScanner.kt)
walks the project content roots (bounded depth, skipping `.*`, `node_modules`,
`venv`). A **suite** is any directory containing `suite.conf`; **test cases** are
its `tst_*` sub-directories. `suite.conf`'s `TEST_CASES` key defines ordering
when present, otherwise tests are sorted alphabetically. Each test's script is
the first of `test.py`, `test.js`, … found in its directory. The parsed model is
[SquishModel.kt](src/main/kotlin/com/pysquish/model/SquishModel.kt).

## How running works

[SquishCommandBuilder](src/main/kotlin/com/pysquish/execution/SquishCommandBuilder.kt)
builds the command:

```
<squishrunner> [--host H --port P] --testsuite <suiteDir> [--testcase <name>] <extra args>
```

`--host/--port` are emitted only when a squishserver is started or the *Connect
to a running squishserver* option is enabled, and always precede `--testsuite`
(squishrunner rejects them after the suite).

run from the suite directory. PySquish also appends `--reportgen <gen>,<tmp>`
(in addition to the user's own `--reportgen`) so it can parse a structured report
for the Report tab and per-test verdicts. `<gen>` is the **Report generator**
setting (default `xml3.4`); it must be a Squish-native XML format your
`squishrunner` supports (not `xmljunit`).

[SquishTestRunner](src/main/kotlin/com/pysquish/execution/SquishTestRunner.kt)
optionally starts the server and launches the runner with an `OSProcessHandler`.
Instead of a raw `attachToProcess`, a
[SquishConsolePrinter](src/main/kotlin/com/pysquish/execution/SquishConsolePrinter.kt)
buffers output into whole lines and prints each (through
[SquishConsole](src/main/kotlin/com/pysquish/execution/SquishConsole.kt), which
remembers lines so the level filter can re-render) in a level-based color. When the
process ends it parses the temp `xml3.4` report
([report/](src/main/kotlin/com/pysquish/report/)), pushes it to the Report tab and
the per-test badges, tears down the server, (for debug runs) stops the Python
Debug Server, and **deletes the temp `pysquish-report` and `pysquish-debug`
directories**. One run is active at a time.

## Debugging (PyCharm debugger ↔ Squish Python)

Squish runs its own embedded Python, so the integration uses the standard
**`pydevd` remote-attach** model
([SquishDebugSupport](src/main/kotlin/com/pysquish/debug/SquishDebugSupport.kt)):

1. PySquish starts PyCharm's **Python Debug Server** (listens on *Debug port*).
   This is best-effort via the `PyRemoteDebugConfigurationType`; if it can't
   auto-start, the console prints instructions to start one manually.
2. PySquish drops a generated `sitecustomize.py` on `PYTHONPATH` (plus a
   `pysquish_debug` module) and sets `PYSQUISH_DEBUG=1` for the run. Because
   CPython's `site` initialization imports `sitecustomize`, the Squish
   interpreter auto-attaches when that variable is set — **no edits to your
   test scripts** — calling `pydevd_pycharm.settrace(host, port=port,
   suspend=False)` with stdout/stderr forwarded to the IDE. Breakpoints you set
   in the IDE then pause the run.
3. `pydevd_pycharm` must be importable by the Squish interpreter. Set
   **pydevd_pycharm path** to a directory containing it, or
   `pip install pydevd-pycharm` into the interpreter Squish uses. PySquish also
   probes the bundled PyCharm helpers (`plugins/python*/helpers/pydev`).

**Fallbacks / caveats**

- **Community edition can't debug.** The *Python Debug Server* config type is
  Professional-only, so `startDebugServer` finds nothing and prints manual help
  that Community users can't act on. Breakpoints require Professional; **Run** is
  unaffected.
- **Real Squish may reset tracing.** The startup `sitecustomize` attach can be
  undone when `squishrunner` initializes; the socket stays up (logs still flow)
  but breakpoints never bind. Attach from inside the test —
  `import pysquish_debug; pysquish_debug.attach()` at the top of `main()` — to
  re-arm tracing on the thread that runs the test. This is also the fix when
  Squish runs Python with `-S` (no site).
- When the run ends, PySquish stops the Python Debug Server it started
  (`SquishDebugSupport.stopDebugServer`), so the debugger doesn't linger.
- Auto-start runs asynchronously, so on the very first debug run the server may
  still be coming up when the test connects. If attach fails, keep the **PySquish
  Debug Server** run config listening and re-run — it will reconnect.
- The `PyRemoteDebugConfigurationType` id and the config's port field are set via
  the EP list and reflection to survive minor API changes; verify against your
  exact PyCharm build if auto-start misbehaves.

## Source map

| Area | File |
|------|------|
| Plugin manifest | [plugin.xml](src/main/resources/META-INF/plugin.xml) |
| Settings (model + UI) | [settings/](src/main/kotlin/com/pysquish/settings/) |
| Suite/test discovery | [model/](src/main/kotlin/com/pysquish/model/) |
| Suite/test scaffolding | [SquishScaffolder.kt](src/main/kotlin/com/pysquish/model/SquishScaffolder.kt), [NewSuiteDialog.kt](src/main/kotlin/com/pysquish/toolwindow/NewSuiteDialog.kt), [test.py.mustache](src/main/resources/templates/test.py.mustache) |
| Command building + execution | [execution/](src/main/kotlin/com/pysquish/execution/) |
| Console coloring / buffering | [SquishConsolePrinter.kt](src/main/kotlin/com/pysquish/execution/SquishConsolePrinter.kt), [SquishConsole.kt](src/main/kotlin/com/pysquish/execution/SquishConsole.kt) |
| Phase/Step (PX-SX) tracking | [execution/SquishSectionTracker.kt](src/main/kotlin/com/pysquish/execution/SquishSectionTracker.kt) |
| Clickable script locations | [SquishScriptLocator.kt](src/main/kotlin/com/pysquish/execution/SquishScriptLocator.kt), [SquishLocationFilter.kt](src/main/kotlin/com/pysquish/execution/SquishLocationFilter.kt) |
| Report model + xml3.4 parser | [report/](src/main/kotlin/com/pysquish/report/) |
| Debugger integration | [debug/SquishDebugSupport.kt](src/main/kotlin/com/pysquish/debug/SquishDebugSupport.kt) |
| Tool window UI (+ Report tab, badges) | [toolwindow/](src/main/kotlin/com/pysquish/toolwindow/) |

## Known limitations / next steps

- The Report tree and console colors are driven by the `xml3.4` report schema and
  the stdout level tokens; both are isolated but should be validated against real
  `squishrunner` output. A natural next step is mapping the report into the IDE's
  native test-runner UI.
- Per-test verdict badges are session-only (not persisted across restarts).
- Server lifecycle is minimal (start before, kill after). No reuse of an
  already-running server beyond what `--host/--port` provides.
- `untilBuild` is open; run `verifyPlugin` when adopting a new major PyCharm.
- Tested logic (arg parsing) has unit tests; UI and process wiring are not yet
  covered by integration tests.
