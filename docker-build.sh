#!/usr/bin/env bash
#
# Builds the PySquish plugin inside Docker — no local JDK, Gradle, or IntelliJ
# needed. The PyCharm SDK is downloaded by Gradle inside the container.
#
# Output: dist/pysquish-<version>.zip on the host.
# Install in PyCharm via Settings | Plugins | gear | "Install Plugin from Disk…".
#
# Usage:
#   ./docker-build.sh
#
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is not installed or not on PATH." >&2
  exit 1
fi

mkdir -p dist

echo "Building plugin in Docker (first run downloads the PyCharm SDK; later runs are cached)…"
DOCKER_BUILDKIT=1 docker build \
  --target artifact \
  --output "type=local,dest=dist" \
  .

ARTIFACT="$(ls -t dist/*.zip 2>/dev/null | head -n 1 || true)"
if [ -z "$ARTIFACT" ]; then
  echo "ERROR: build completed but no zip landed in dist/." >&2
  exit 1
fi

echo
echo "============================================================"
echo " Plugin built: $ARTIFACT"
echo
echo " Install in PyCharm:"
echo "   Settings | Plugins | (gear) | Install Plugin from Disk…"
echo "   pick the .zip above, then restart the IDE."
echo "============================================================"
