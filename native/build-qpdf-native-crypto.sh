#!/usr/bin/env bash
# Build libqpdf from source with --with-crypto=native, dropping the crypto
# transitive deps the system libqpdf otherwise drags into the JPDFium
# bundle:
#   Linux  (apt libqpdf-dev, GNUTLS-built): libgnutls + libnettle +
#       libgmp + libp11-kit + libidn2 + libunistring + libtasn1 +
#       libbrotli* ≈ 7-8 MB
#   macOS  (brew libqpdf, OpenSSL-built):   libcrypto.3.dylib ≈ 4.85 MB
#
# qpdf supports three crypto backends:
#   --with-crypto=native  : qpdf's own AES, RC4, SHA - zero external deps
#   --with-crypto=openssl : pulls libssl/libcrypto (~5 MB)
#   --with-crypto=gnutls  : pulls the gnutls chain (~8-9 MB) (apt default)
#
# JPDFium uses qpdf only for structure inspection / repair via QPDF and
# QPDFObjectHandle (jpdfium_repair.cpp, jpdfium_advanced.cpp). Password-
# protected PDFs are handled via FPDF_LoadDocument (PDFium), not qpdf -
# so the native backend's AES/RC4 is fully sufficient for our use.
#
# Output:
#   Linux  - /usr/local/lib/libqpdf.so.<v> + headers + libqpdf.pc
#   macOS  - $(brew --prefix)/lib/libqpdf.<v>.dylib + headers + libqpdf.pc
# Both picked up by the bridge's pkg_check_modules(libqpdf) via the
# default pkg-config search path. The original (apt / brew) libqpdf
# files are moved aside so the bundler's ldd/otool walk resolves to
# our build.

echo "build-qpdf-native-crypto.sh: start  ($(uname -s) $(uname -m))"

set -u

case "$(uname -s)" in
    Linux*)
        OS=linux
        PREFIX=/usr/local
        SUDO=sudo
        ;;
    Darwin*)
        OS=darwin
        # On macos-14 the matrix has TWO macOS jobs:
        #   darwin-arm64  - native, uses the host's arm64 brew at
        #                   /opt/homebrew (default `brew --prefix`)
        #   darwin-x64    - Rosetta cross-compile, uses a SECOND brew
        #                   installed at /usr/local (the x64 default).
        #                   CMAKE_OSX_ARCHITECTURES=x86_64 is set by the
        #                   workflow's deps step; PKG_CONFIG points at
        #                   /usr/local/bin/pkg-config; etc.
        # Pick the right brew + ARCH-prefix the build accordingly so we
        # produce a same-arch dylib that the bridge's pkg_check_modules
        # will actually resolve to.
        if [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
            # Cross-compile from arm64 host. CMAKE_OSX_ARCHITECTURES=x86_64
            # already set by the workflow makes clang emit x86_64 code; we
            # just need to use the matching brew prefix at /usr/local so
            # the build resolves x86_64 deps (zlib / jpeg / etc) instead
            # of accidentally picking up arm64 ones from /opt/homebrew.
            PREFIX=/usr/local
            if [ ! -x /usr/local/bin/brew ]; then
                echo "build-qpdf-native-crypto.sh: x86_64 brew (Rosetta) not installed; skipping" >&2
                exit 0
            fi
        else
            if ! command -v brew >/dev/null 2>&1; then
                echo "build-qpdf-native-crypto.sh: brew not on PATH on macOS; skipping" >&2
                exit 0
            fi
            PREFIX=$(brew --prefix)
        fi
        # macOS runner owns brew prefix as the runner user, so no sudo.
        SUDO=
        ;;
    *)
        echo "build-qpdf-native-crypto.sh: skipping on $(uname -s)"
        exit 0
        ;;
esac

resolve_latest_tag() {
    local repo="$1"
    curl -fsSL --retry 3 --retry-delay 3 \
        "https://api.github.com/repos/$repo/releases/latest" 2>/dev/null \
        | grep -oE '"tag_name": *"[^"]+"' | head -1 \
        | sed -E 's/.*"([^"]+)"$/\1/' || true
}

if [ -z "${QPDF_TAG:-}" ]; then
    QPDF_TAG="$(resolve_latest_tag qpdf/qpdf)"
    QPDF_TAG="${QPDF_TAG:-v12.4.0}"
    echo "==> build-qpdf-native-crypto.sh: qpdf resolved to latest: $QPDF_TAG"
else
    echo "==> build-qpdf-native-crypto.sh: qpdf pinned by env: $QPDF_TAG"
fi
QPDF_REPO=https://github.com/qpdf/qpdf

# Build prerequisites: zlib + libjpeg are needed by qpdf regardless of
# crypto backend. Both should already be installed via the platform's
# build deps (apt libjpeg-turbo / brew jpeg-turbo).
for pc in zlib libjpeg; do
    if ! pkg-config --exists "$pc"; then
        if [ "$pc" = "libjpeg" ] && pkg-config --exists libjpeg-turbo; then
            continue
        fi
        echo "build-qpdf-native-crypto.sh: missing pkg-config $pc; skipping" >&2
        exit 0
    fi
done

if ! command -v cmake >/dev/null 2>&1; then
    echo "build-qpdf-native-crypto.sh: cmake not installed; skipping" >&2
    exit 0
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "Cloning qpdf ${QPDF_TAG}..."
if ! git clone --depth 1 -b "$QPDF_TAG" "$QPDF_REPO" "$WORK/qpdf"; then
    echo "build-qpdf-native-crypto.sh: git clone failed; skipping" >&2
    exit 0
fi

NPROC="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

CMAKE_EXTRA_ARGS=()
if [ "$OS" = "darwin" ]; then
    # On macOS, force install_name to be the absolute /opt/homebrew/lib path
    # (default would be @rpath/libqpdf.<v>.dylib). bundle-runtime-deps.sh's
    # bundle_macos walks otool -L and SKIPS @-prefixed deps (it assumes they
    # are already-relativized references that some earlier step handled),
    # so an @rpath install_name causes the bundler to silently drop libqpdf
    # from the natives jar - the bridge then fails to dlopen libqpdf at
    # runtime. Absolute install_name keeps the dep visible to otool and the
    # bundler picks it up like any other lib; install_name_tool rewrites it
    # to @loader_path/libqpdf.<v>.dylib at bundle time.
    CMAKE_EXTRA_ARGS+=("-DCMAKE_INSTALL_NAME_DIR=$PREFIX/lib")
    CMAKE_EXTRA_ARGS+=("-DCMAKE_MACOSX_RPATH=OFF")
fi

cmake -S "$WORK/qpdf" -B "$WORK/qpdf/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DDEFAULT_CRYPTO=native \
    -DREQUIRE_CRYPTO_NATIVE=1 \
    -DREQUIRE_CRYPTO_GNUTLS=0 \
    -DREQUIRE_CRYPTO_OPENSSL=0 \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_STATIC_LIBS=OFF \
    -DUSE_IMPLICIT_CRYPTO=OFF \
    -DBUILD_DOC=OFF -DBUILD_DOC_HTML=OFF -DBUILD_DOC_PDF=OFF \
    -DBUILD_DOC_DIST=OFF \
    -DCI_MODE=ON \
    "${CMAKE_EXTRA_ARGS[@]}" \
  || { echo "build-qpdf-native-crypto.sh: cmake configure failed; skipping" >&2; exit 0; }

cmake --build "$WORK/qpdf/build" --target libqpdf -j"$NPROC" \
  || { echo "build-qpdf-native-crypto.sh: cmake build failed; skipping" >&2; exit 0; }

# Install lib + dev components separately (cmake --install takes a single
# --component at a time).
$SUDO cmake --install "$WORK/qpdf/build" --component lib \
  || { echo "build-qpdf-native-crypto.sh: install lib failed; skipping" >&2; exit 0; }
$SUDO cmake --install "$WORK/qpdf/build" --component dev \
  || { echo "build-qpdf-native-crypto.sh: install dev failed; skipping" >&2; exit 0; }
if [ "$OS" = "linux" ]; then
    $SUDO ldconfig 2>/dev/null || true
fi

# Diagnostics - confirm libcrypto/libgnutls/libssl aren't pulled in.
if [ "$OS" = "linux" ]; then
    NEW_QPDF=$(find "$PREFIX/lib" -maxdepth 1 -name "libqpdf.so.*" -type f 2>/dev/null | head -1)
    if [ -n "$NEW_QPDF" ]; then
        echo "Installed: $NEW_QPDF ($(du -h "$NEW_QPDF" | cut -f1))"
        echo "ldd:"
        ldd "$NEW_QPDF" | sed 's/^/  /'
    fi
else
    NEW_QPDF=$(find "$PREFIX/lib" -maxdepth 1 -name "libqpdf.*.dylib" -type f 2>/dev/null | head -1)
    if [ -n "$NEW_QPDF" ]; then
        echo "Installed: $NEW_QPDF ($(du -h "$NEW_QPDF" | cut -f1))"
        echo "otool -L:"
        otool -L "$NEW_QPDF" | sed 's/^/  /'
    fi
fi

# Move the original libqpdf aside so the bundler picks up our build.
# Linux: apt libs under /usr/lib/<triplet>/. macOS: the install above
# already overwrote brew's canonical names in $(brew --prefix)/lib/;
# the Cellar copies aren't on the default linker search path so they
# don't get picked up.
if [ "$OS" = "linux" ]; then
    for old in /usr/lib/x86_64-linux-gnu/libqpdf.so* /usr/lib/aarch64-linux-gnu/libqpdf.so*; do
        [ -e "$old" ] || continue
        sudo mv "$old" "${old}.disabled" 2>/dev/null || true
    done
fi
