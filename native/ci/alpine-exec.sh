#!/usr/bin/env bash
# Runs musl build steps inside an Alpine container with the repository mounted at /src.
set -euo pipefail

PLATFORM="${1:?usage: alpine-exec.sh <platform> <step> [<step> ...]}"
shift

# eclipse-temurin:25-jdk-alpine-3.23 = musl JDK on Alpine: the toolchain for the
# linux-musl-* targets. Pulls the matching-arch manifest on both x64 and arm64
# runners (native, no QEMU).
#
# The Alpine release is pinned deliberately: the floating 25-jdk-alpine tag now
# tracks Alpine 3.24, whose python3 3.14 is refused by the gsutil 5.35 that
# depot_tools downloads ("gsutil requires Python version 3.9-3.13"). That aborts
# the GCS deps of gclient sync (bundled clang, rust toolchain) on every musl row.
# Alpine 3.23 ships python3 3.12.15, which gsutil supports.
IMAGE="eclipse-temurin:25-jdk-alpine-3.23"

ENV_ARGS=()
for var in GH_TOKEN GITHUB_REPOSITORY EMBEDPDF_PIN_SHA \
           JPDFIUM_BUILD_MODE JPDFIUM_TARGET_PLATFORM JPDFIUM_LIBC \
           JPDFIUM_SKIP_SIZE_BUDGET SIZE_GATE_STRICT; do
    if [ -n "${!var:-}" ]; then
        ENV_ARGS+=(-e "$var")
    fi
done

docker run --rm \
  -v "$PWD":/src -w /src \
  ${ENV_ARGS[@]+"${ENV_ARGS[@]}"} \
  "$IMAGE" \
  /bin/sh -c 'apk add --no-cache bash >/dev/null 2>&1; exec bash native/ci/musl.sh "$@"' \
  alpine-exec "$PLATFORM" "$@"
