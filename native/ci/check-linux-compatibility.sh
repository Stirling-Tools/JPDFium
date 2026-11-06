#!/usr/bin/env bash
# Runs the staged Linux artifacts on the minimum and current Ubuntu runtimes.
set -euo pipefail

PLATFORM="${1:?usage: check-linux-compatibility.sh <linux-x64|linux-arm64> [core|vips]}"
SUITE="${2:-core}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
case "$PLATFORM" in
    linux-x64) DOCKER_PLATFORM=linux/amd64 ;;
    linux-arm64) DOCKER_PLATFORM=linux/arm64 ;;
    *) echo "ERROR: unsupported glibc platform: $PLATFORM" >&2; exit 1 ;;
esac
case "$SUITE" in
    core)
        TASK=:jpdfium:nativeCompatibilityTest
        CLEAN=:jpdfium:cleanNativeCompatibilityTest
        REPORT=jpdfium/build/test-results/nativeCompatibilityTest
        ;;
    vips)
        TASK=:jpdfium-vips:vipsSmokeTest
        CLEAN=:jpdfium-vips:cleanVipsSmokeTest
        REPORT=jpdfium-vips/build/test-results/vipsSmokeTest
        ;;
    *) echo "ERROR: unsupported test suite: $SUITE" >&2; exit 1 ;;
esac

CACHE="jpdfium-compat-gradle-$PLATFORM-$(id -u)"
docker run --rm --platform "$DOCKER_PLATFORM" \
    --mount "type=volume,source=$CACHE,target=/gradle" \
    eclipse-temurin:25-jdk-noble chown "$(id -u):$(id -g)" /gradle
cd "$ROOT"
for runtime in noble resolute; do
    echo "Checking $SUITE $PLATFORM on Ubuntu $runtime"
    docker run --rm --platform "$DOCKER_PLATFORM" \
        --user "$(id -u):$(id -g)" \
        --mount "type=bind,source=$ROOT,target=/workspace" \
        --mount "type=volume,source=$CACHE,target=/gradle" \
        --workdir /workspace --env HOME=/tmp --env GRADLE_USER_HOME=/gradle \
        "eclipse-temurin:25-jdk-$runtime" \
        ./gradlew "$CLEAN" "$TASK" \
            "-Pjpdfium.testNatives=$PLATFORM" \
            "-Pjpdfium.testVipsNatives=vips-$PLATFORM" \
            --no-daemon --no-build-cache --stacktrace
    bash native/ci/check-smoke-ran.sh "$REPORT" --no-skips
    if [ "$SUITE" = core ]; then
        for test in NativeSmokeTest PdfMergeSplitPreservationTest; do
            test -f "$REPORT/TEST-stirling.software.jpdfium.$test.xml" || {
                echo "FAIL: missing $test report on $runtime" >&2
                exit 1
            }
        done
    fi
    mkdir -p "$REPORT-$runtime"
    cp -R "$REPORT/." "$REPORT-$runtime/"
done
