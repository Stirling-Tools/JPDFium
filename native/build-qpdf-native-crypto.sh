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
#   Linux  - /usr/local/lib/libqpdf.a + headers + libqpdf.pc
#   macOS  - $(brew --prefix)/lib/libqpdf.a + headers + libqpdf.pc
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
        # Containers often run as root without sudo installed; CI runners are
        # non-root and need it for /usr/local.
        if [ "$(id -u)" = "0" ]; then SUDO=""; else SUDO=sudo; fi
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
        echo "build-qpdf-native-crypto.sh: missing pkg-config $pc" >&2
        exit 1
    fi
done

if ! command -v cmake >/dev/null 2>&1; then
    echo "build-qpdf-native-crypto.sh: cmake not installed" >&2
    exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "Cloning qpdf ${QPDF_TAG}..."
if ! git clone --depth 1 -b "$QPDF_TAG" "$QPDF_REPO" "$WORK/qpdf"; then
    echo "build-qpdf-native-crypto.sh: git clone failed" >&2
    exit 1
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

# Static archive: the bridge uses a small slice of qpdf, so the final link
# can dead-strip unused objects/functions instead of shipping a complete
# libqpdf dylib (2.5 MB) in every natives jar. PIC is required because the
# archive ends up inside libjpdfium.so/.dylib.
cmake -S "$WORK/qpdf" -B "$WORK/qpdf/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DDEFAULT_CRYPTO=native \
    -DREQUIRE_CRYPTO_NATIVE=1 \
    -DREQUIRE_CRYPTO_GNUTLS=0 \
    -DREQUIRE_CRYPTO_OPENSSL=0 \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_STATIC_LIBS=ON \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_C_FLAGS="-fPIC -ffunction-sections -fdata-sections" \
    -DCMAKE_CXX_FLAGS="-fPIC -ffunction-sections -fdata-sections" \
    -DUSE_IMPLICIT_CRYPTO=OFF \
    -DBUILD_DOC=OFF -DBUILD_DOC_HTML=OFF -DBUILD_DOC_PDF=OFF \
    -DBUILD_DOC_DIST=OFF \
    -DCI_MODE=ON \
    "${CMAKE_EXTRA_ARGS[@]}" \
  || { echo "build-qpdf-native-crypto.sh: cmake configure failed" >&2; exit 1; }

cmake --build "$WORK/qpdf/build" --target libqpdf -j"$NPROC" \
  || { echo "build-qpdf-native-crypto.sh: cmake build failed" >&2; exit 1; }

# Install lib + dev components separately (cmake --install takes a single
# --component at a time).
$SUDO cmake --install "$WORK/qpdf/build" --component lib \
  || { echo "build-qpdf-native-crypto.sh: install lib failed" >&2; exit 1; }
$SUDO cmake --install "$WORK/qpdf/build" --component dev \
  || { echo "build-qpdf-native-crypto.sh: install dev failed" >&2; exit 1; }
if [ "$OS" = "linux" ]; then
    $SUDO ldconfig 2>/dev/null || true
fi

# Point pkg-config at the archive itself. A plain -lqpdf would resolve to
# the distro/brew shared library that still sits beside it, defeating the
# dead-strip and pulling libqpdf back into the bundle.
QPDF_STATIC="$PREFIX/lib/libqpdf.a"
QPDF_PC="$PREFIX/lib/pkgconfig/libqpdf.pc"
if [ ! -f "$QPDF_STATIC" ] || [ ! -f "$QPDF_PC" ]; then
    echo "build-qpdf-native-crypto.sh: static archive/pkg-config missing after install" >&2
    exit 1
fi
JPEG_ARGS="-ljpeg"
if [ "$OS" = "darwin" ]; then
    JPEG_PREFIX="$PREFIX/opt/jpeg-turbo"
    JPEG_ARGS="-L$JPEG_PREFIX/lib -ljpeg"
fi
$SUDO python3 - "$QPDF_PC" "$QPDF_STATIC" "$JPEG_ARGS" <<'PY_EOF'
import re, sys
path, archive, jpeg = sys.argv[1:4]
with open(path) as f:
    text = f.read()
text = re.sub(r'(?m)^Requires\.private:.*\n?', '', text)
text = re.sub(r'(?m)^Libs\.private:.*\n?', '', text)
libs = f"Libs: -L{archive.rsplit('/libqpdf.a', 1)[0]} -lqpdf {jpeg} -lz"
if re.search(r'(?m)^Libs:', text):
    text = re.sub(r'(?m)^Libs:.*$', libs, text, count=1)
else:
    text += libs + "\n"
with open(path, 'w') as f:
    f.write(text)
print(f"Rewrote {path} Libs -> {libs}")
PY_EOF

echo "Installed static qpdf: $QPDF_STATIC ($(du -h "$QPDF_STATIC" | cut -f1))"
echo "pkg-config libs: $(pkg-config --libs libqpdf 2>/dev/null || true)"

# The linker prefers a shared libqpdf over the static archive when both are
# on the search path, which would put the 2.5 MB dylib straight back into the
# bundle. Disable the shared copies now that the bridge is built against the
# archive; the pc file points at -lqpdf and resolves to libqpdf.a.
if [ "$OS" = "linux" ]; then
    for old in /usr/lib/x86_64-linux-gnu/libqpdf.so* /usr/lib/aarch64-linux-gnu/libqpdf.so*                /usr/local/lib/libqpdf.so*; do
        [ -e "$old" ] || continue
        $SUDO mv "$old" "${old}.disabled" 2>/dev/null || true
    done
else
    for old in "$PREFIX"/lib/libqpdf*.dylib; do
        { [ -e "$old" ] || [ -L "$old" ]; } || continue
        mv "$old" "${old}.disabled" 2>/dev/null || true
    done
fi
