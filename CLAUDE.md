# PySquish — PyCharm plugin for running Squish tests

PySquish is an IntelliJ Platform plugin (PyCharm 2025.1+) that discovers
[Squish](https://www.qt.io/product/quality-assurance/squish) test suites in the
opened project, lets you pick a suite and run any test case from a tool window,
streams `squishrunner` output into a log console, and can attach the PyCharm
Python debugger to the running test so breakpoints are honored.

## Status

v0.2.0 — adds per-test result badges, a colored console, a structured **Report**
tab (parsed from the Squish `xml3.5` report), and automatic teardown of the
Python Debug Server when a run ends. Builds with `./gradlew buildPlugin`. The
console coloring and XML report parsing are **format-driven** — the level
keywords and the `xml3.5` element handling are isolated (see
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
- **Debug host / port** and **pydevd_pycharm path** — see *Debugging*.

## Usage

1. Open a project containing Squish suites.
2. Open the **PySquish** tool window (bottom).
3. It scans for suites automatically; the toolbar **Refresh** rescans.
4. Pick a suite in the combo box. Each test case shows **Run** and **Debug**
   buttons; the toolbar has **Run Whole Suite**, **Stop**, and a **Settings**
   shortcut. After a run, each test shows its last verdict — a green ✓ (OK) or
   red ✗ (KO) — next to its name (session-only).
5. The right side has two tabs:
   - **Console** — live `squishrunner` output, colored by level (`PASS` green,
     `FAIL`/`ERROR` red, `WARNING` orange, `INFO` blue, `LOG` grey).
   - **Report** — a foldable tree parsed from the Squish `xml3.5` report:
     `startSection`/`endSection` become collapsible layers, entries are
     iconed/colored by type, and sections containing a failure auto-expand.

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

run from the suite directory. PySquish also appends `--reportgen xml3.5,<tmp>`
(in addition to the user's own `--reportgen`) so it can parse a structured report
for the Report tab and per-test verdicts.

[SquishTestRunner](src/main/kotlin/com/pysquish/execution/SquishTestRunner.kt)
optionally starts the server and launches the runner with an `OSProcessHandler`.
Instead of a raw `attachToProcess`, a
[SquishConsolePrinter](src/main/kotlin/com/pysquish/execution/SquishConsolePrinter.kt)
buffers output into whole lines and prints each in a level-based color. When the
process ends it parses the temp `xml3.5` report
([report/](src/main/kotlin/com/pysquish/report/)), pushes it to the Report tab and
the per-test badges, tears down the server, and (for debug runs) stops the Python
Debug Server. One run is active at a time.

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
| Command building + execution | [execution/](src/main/kotlin/com/pysquish/execution/) |
| Console coloring | [execution/SquishConsolePrinter.kt](src/main/kotlin/com/pysquish/execution/SquishConsolePrinter.kt) |
| Report model + xml3.5 parser | [report/](src/main/kotlin/com/pysquish/report/) |
| Debugger integration | [debug/SquishDebugSupport.kt](src/main/kotlin/com/pysquish/debug/SquishDebugSupport.kt) |
| Tool window UI (+ Report tab, badges) | [toolwindow/](src/main/kotlin/com/pysquish/toolwindow/) |

## Known limitations / next steps

- The Report tree and console colors are driven by the `xml3.5` report schema and
  the stdout level tokens; both are isolated but should be validated against real
  `squishrunner` output. A natural next step is mapping the report into the IDE's
  native test-runner UI.
- Per-test verdict badges are session-only (not persisted across restarts).
- Server lifecycle is minimal (start before, kill after). No reuse of an
  already-running server beyond what `--host/--port` provides.
- `untilBuild` is open; run `verifyPlugin` when adopting a new major PyCharm.
- Tested logic (arg parsing) has unit tests; UI and process wiring are not yet
  covered by integration tests.
