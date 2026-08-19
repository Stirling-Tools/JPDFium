#!/usr/bin/env bash
# Runs native build steps inside an Alpine/musl container.
set -euo pipefail

PLATFORM="${1:?usage: musl.sh <platform> <step> [<step> ...]}"
shift

echo "==> musl.sh [$PLATFORM] bootstrapping Alpine toolchain"
apk add --no-cache bash build-base clang lld cmake ninja pkgconf \
  meson python3 git curl tar xz gcompat libstdc++ linux-headers \
  sudo patchelf coreutils file binutils \
  pcre2-dev freetype-dev harfbuzz-dev icu-dev icu icu-data-full \
  qpdf-dev pugixml-dev libunibreak-dev zlib-dev libjpeg-turbo-dev \
  rust cargo github-cli \
  py3-pip py3-httplib2 py3-six

stage_and_bundle() {
    local plat="$1"
    mkdir -p "native/dist/$plat"
    # Bridge: libjpdfium.so (Linux/macOS) or jpdfium.dll (Windows - n/a for musl).
    find native/build-real -maxdepth 3 -type f \( \
        -name 'libjpdfium.*' -o -name 'jpdfium.dll' \
    \) -exec cp -v {} "native/dist/$plat/" \;
    # Component-built PDFium siblings (libabsl, icuuc, libpng, ...), filtering
    # out Windows debug helpers (dbghelp/msdia/symsrv/dbgcore/vccorlib).
    if [ -d native/pdfium/lib ]; then
        find native/pdfium/lib -maxdepth 1 -type f \( \
            -name '*.dll' -o -name 'lib*.so' -o -name 'lib*.so.*' -o -name 'lib*.dylib' \
        \) \
        ! -name 'dbghelp.dll' ! -name 'dbgcore.dll' \
        ! -name 'msdia*.dll'   ! -name 'symsrv.dll' \
        ! -name 'vccorlib*.dll' \
        -exec cp -v {} "native/dist/$plat/" \;
    fi
    # Walk the bridge's import table (ldd) and copy referenced deps + strip,
    # then run the execstack + hermeticity gates.
    bash native/bundle-runtime-deps.sh "$plat"
    echo "--- staged ($plat) ---"
    ls -la "native/dist/$plat/"
}

for step in "$@"; do
    case "$step" in
        pdfium)
            echo "==> musl.sh [$PLATFORM] pdfium"
            JPDFIUM_LIBC=musl bash native/setup-pdfium.sh
            ;;
        pdfium-smoke)
            echo "==> musl.sh [$PLATFORM] pdfium-smoke"
            cc native/ci/pdfium_smoke.c -ldl -o /tmp/pdfium_smoke
            LD_LIBRARY_PATH=native/pdfium/lib /tmp/pdfium_smoke \
                native/pdfium/lib/libpdfium.so \
                jpdfium/src/test/resources/pdfs/redact/redact-test-empty.pdf
            ;;
        natives)
            echo "==> musl.sh [$PLATFORM] natives"
            # qpdf native-crypto + harfbuzz-no-glib drop ~8 MB of crypto/glib
            # deps from the bundle; both skip gracefully on failure.
            bash native/build-qpdf-native-crypto.sh
            bash native/build-harfbuzz-no-glib.sh
            bash native/build-real.sh
            JPDFIUM_TARGET_PLATFORM="$PLATFORM" bash native/build-minimal-icu.sh
            stage_and_bundle "$PLATFORM"
            ;;
        smoke)
            echo "==> musl.sh [$PLATFORM] smoke"
            ./gradlew :jpdfium:nativeSmokeTest \
                -Pjpdfium.testNatives="$PLATFORM" --no-daemon --stacktrace
            ;;
        probe)
            echo "==> musl.sh [$PLATFORM] probe"
            bash native/build-stub.sh "$PLATFORM"
            ./gradlew :jpdfium-natives:jpdfium-natives-"$PLATFORM":jar --no-daemon
            ./gradlew :jpdfium:nativeSmokeTest \
                -Pjpdfium.testNatives="$PLATFORM" --no-daemon --stacktrace
            ;;
        *)
            echo "ERROR: unknown step '$step'" >&2
            exit 2
            ;;
    esac
done

echo "==> musl.sh [$PLATFORM] done"
