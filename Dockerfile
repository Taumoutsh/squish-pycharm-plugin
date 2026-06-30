# syntax=docker/dockerfile:1
#
# Builds the PySquish plugin zip inside a container. The only thing needed is a
# JDK 21 — the IntelliJ Platform Gradle plugin downloads the PyCharm SDK itself,
# so no IntelliJ/PyCharm installation is required.
#
# Recommended one-liner (writes the zip into ./dist on the host, no container
# left running):
#
#   DOCKER_BUILDKIT=1 docker build --target artifact --output type=local,dest=dist .
#
# Or just use ./docker-build.sh which wraps this.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Copy everything and build. The cache mounts keep the (large) IntelliJ platform
# download and Gradle/Kotlin caches across builds so repeat builds are fast.
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/src/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon clean buildPlugin && \
    mkdir -p /out && cp build/distributions/*.zip /out/

# Minimal stage whose only contents are the built artifact, so
# `--output type=local,dest=dist` drops the zip straight into ./dist.
FROM scratch AS artifact
COPY --from=build /out/ /
