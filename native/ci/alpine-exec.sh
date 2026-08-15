#!/usr/bin/env bash
# Execute musl/Alpine build steps inside an Alpine container matching the
# platform, with the repo mounted at /src.
#
# GitHub Actions cannot run JavaScript actions (checkout, upload-artifact, ...)
# inside Alpine containers on arm64 runners ("JavaScript Actions in Alpine
# containers are only supported on x64 Linux runners"), so instead of a
# job-level `container:` we run every musl-leg build step via docker run from
# the host. The checkout/setup/upload actions then run natively on the host
# runner (x64 or arm64) and only the actual build + smoke happens inside musl.
#
# Usage: alpine-exec.sh <platform> <step> [<step> ...]
#   e.g.  alpine-exec.sh linux-musl-x64 natives smoke
#
# All steps run in ONE container so the Alpine bootstrap (apk add ~1 GB) happens
# once. Env vars the steps need (GH_TOKEN, GITHUB_REPOSITORY, EMBEDPDF_PIN_SHA,
# JPDFIUM_BUILD_MODE, JPDFIUM_TARGET_PLATFORM, JPDFIUM_LIBC) are forwarded from
# the host when set.
set -euo pipefail

PLATFORM="${1:?usage: alpine-exec.sh <platform> <step> [<step> ...]}"
shift

# eclipse-temurin:25-jdk-alpine = musl JDK on Alpine: the toolchain for the
# linux-musl-* targets. Pulls the matching-arch manifest on both x64 and arm64
# runners (native, no QEMU).
IMAGE="eclipse-temurin:25-jdk-alpine"

ENV_ARGS=()
for var in GH_TOKEN GITHUB_REPOSITORY EMBEDPDF_PIN_SHA \
           JPDFIUM_BUILD_MODE JPDFIUM_TARGET_PLATFORM JPDFIUM_LIBC; do
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
