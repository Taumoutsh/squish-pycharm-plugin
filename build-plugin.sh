#!/usr/bin/env bash
#
# Builds the PySquish plugin and prints the installable artifact path.
#
# In PyCharm: Settings | Plugins | gear icon | "Install Plugin from Disk…"
# and select the printed .zip file. (IntelliJ plugins are distributed as a zip,
# even though people often say "jar"; the zip contains the plugin jar + deps.)
#
# Usage:
#   ./build-plugin.sh            # clean build
#   ./build-plugin.sh --fast     # skip clean (incremental)
#
set -euo pipefail

cd "$(dirname "$0")"

# --- Ensure a JDK is available (PyCharm 2025.x needs JDK 21) -----------------
if [ -z "${JAVA_HOME:-}" ]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    # macOS: prefer 21, fall back to the newest installed JDK.
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
    export JAVA_HOME
  fi
fi

if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
  echo "ERROR: No JDK found. Install JDK 21 (e.g. 'brew install openjdk@21') and/or set JAVA_HOME." >&2
  exit 1
fi
[ -n "${JAVA_HOME:-}" ] && echo "Using JAVA_HOME=$JAVA_HOME"

# --- Build -------------------------------------------------------------------
GRADLE_TASKS="clean buildPlugin"
if [ "${1:-}" = "--fast" ]; then
  GRADLE_TASKS="buildPlugin"
fi

echo "Running: ./gradlew $GRADLE_TASKS"
./gradlew $GRADLE_TASKS

# --- Locate and surface the artifact ----------------------------------------
ARTIFACT="$(ls -t build/distributions/*.zip 2>/dev/null | head -n 1 || true)"
if [ -z "$ARTIFACT" ]; then
  echo "ERROR: Build finished but no zip was found in build/distributions/." >&2
  exit 1
fi

mkdir -p dist
cp -f "$ARTIFACT" dist/
COPY="dist/$(basename "$ARTIFACT")"

echo
echo "============================================================"
echo " Plugin built successfully:"
echo "   $ARTIFACT"
echo "   $COPY   (copy for convenience)"
echo
echo " Install in PyCharm:"
echo "   Settings | Plugins | (gear) | Install Plugin from Disk…"
echo "   then pick the .zip above and restart the IDE."
echo "============================================================"
