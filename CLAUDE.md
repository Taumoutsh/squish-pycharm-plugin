# PySquish — PyCharm plugin for running Squish tests

PySquish is an IntelliJ Platform plugin (PyCharm 2025.1+) that discovers
[Squish](https://www.qt.io/product/quality-assurance/squish) test suites in the
opened project, lets you pick a suite and run any test case from a tool window,
streams `squishrunner` output into a log console, and can attach the PyCharm
Python debugger to the running test so breakpoints are honored.

## Status

v0.1.1 — feature-complete for the original brief. **Not yet compiled on this
machine** (no JDK/Gradle was available when it was generated). Build it once in a
JetBrains IDE or with `./gradlew buildPlugin`; expect to fix at most minor
platform-API drift for the exact PyCharm build you target. The debugger
auto-start and `pydevd` auto-attach are best-effort — see *Debugging* below.

## Requirements

- PyCharm 2025.1 or newer (`sinceBuild = 251`, no upper bound). Works in any IDE
  with the Python plugin (depends on `com.intellij.modules.python`).
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
   shortcut.
5. Output streams into the console on the right.

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

run from the suite directory.
[SquishTestRunner](src/main/kotlin/com/pysquish/execution/SquishTestRunner.kt)
optionally starts the server, launches the runner with an `OSProcessHandler`, and
attaches it to the tool window's `ConsoleView`. One run is active at a time; the
server (if started) is torn down when the runner exits.

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

- If Squish runs Python with `-S` (no site), `sitecustomize` won't load — add
  `import pysquish_debug; pysquish_debug.attach()` at the top of a test instead
  (the module is on `PYTHONPATH`).
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
| Debugger integration | [debug/SquishDebugSupport.kt](src/main/kotlin/com/pysquish/debug/SquishDebugSupport.kt) |
| Tool window UI | [toolwindow/](src/main/kotlin/com/pysquish/toolwindow/) |

## Known limitations / next steps

- No structured test-results tree yet — output is the raw runner log. A natural
  next step is parsing `--reportgen` XML into the IDE's test-runner UI.
- Server lifecycle is minimal (start before, kill after). No reuse of an
  already-running server beyond what `--host/--port` provides.
- `untilBuild` is open; run `verifyPlugin` when adopting a new major PyCharm.
- Tested logic (arg parsing) has unit tests; UI and process wiring are not yet
  covered by integration tests.
