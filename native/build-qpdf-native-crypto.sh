#!/usr/bin/env bash
# Build libqpdf from source with --with-crypto=native, dropping the GNUTLS
# transitive deps that Ubuntu's apt libqpdf-dev otherwise drags into the
# JPDFium bundle (libgnutls + libnettle + libgmp + libp11-kit + libidn2 +
# libunistring + libtasn1 + libbrotli* + libglib ≈ 8-9 MB).
#
# qpdf supports three crypto backends:
#   --with-crypto=native  : qpdf's own AES, RC4, SHA — zero external deps
#   --with-crypto=openssl : pulls libssl/libcrypto (~5 MB)
#   --with-crypto=gnutls  : pulls the gnutls chain (~8-9 MB)  <- Ubuntu default
#
# JPDFium uses qpdf only for structure inspection / repair via QPDF and
# QPDFObjectHandle (jpdfium_repair.cpp, jpdfium_advanced.cpp). Password-
# protected PDFs are handled via FPDF_LoadDocument (PDFium), not qpdf — so
# the native backend's AES/RC4 is fully sufficient for our use.
#
# Output: /usr/local/lib/libqpdf.so.<v>, /usr/local/include/qpdf/*.h,
#         /usr/local/lib/pkgconfig/libqpdf.pc — picked up by the bridge's
#         pkg_check_modules(libqpdf) ahead of /usr/lib via /usr/local
#         precedence in pkg-config's default search path.
#
# Usage: build-qpdf-native-crypto.sh        # Linux only

echo "build-qpdf-native-crypto.sh: start  ($(uname -s) $(uname -m))"

case "$(uname -s)" in
    Linux*) ;;
    *) echo "build-qpdf-native-crypto.sh: skipping on $(uname -s)"; exit 0;;
esac

set -u

QPDF_TAG="${QPDF_TAG:-v11.9.1}"   # match the v11 series that Ubuntu Noble ships
QPDF_REPO=https://github.com/qpdf/qpdf

# Build prerequisites: zlib + libjpeg are needed by qpdf no matter the
# crypto backend. Both are already installed via libfreetype6-dev /
# libjpeg-turbo via PDFium's component build, so just verify presence.
for pc in zlib libjpeg; do
    if ! pkg-config --exists "$pc"; then
        # Try the libjpeg-turbo name too — Ubuntu ships libjpeg-turbo with
        # alias libjpeg.pc, but on minimal images that alias may be missing.
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
trap "rm -rf '$WORK'" EXIT

echo "Cloning qpdf ${QPDF_TAG}..."
if ! git clone --depth 1 -b "$QPDF_TAG" "$QPDF_REPO" "$WORK/qpdf"; then
    echo "build-qpdf-native-crypto.sh: git clone failed; skipping" >&2
    exit 0
fi

cmake -S "$WORK/qpdf" -B "$WORK/qpdf/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX=/usr/local \
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
  || { echo "build-qpdf-native-crypto.sh: cmake configure failed; skipping" >&2; exit 0; }

cmake --build "$WORK/qpdf/build" --target libqpdf -j"$(nproc)" \
  || { echo "build-qpdf-native-crypto.sh: cmake build failed; skipping" >&2; exit 0; }

# Install both runtime libs and dev headers/.pc files. `cmake --install`
# takes a single --component at a time, so call it twice.
sudo cmake --install "$WORK/qpdf/build" --component lib \
  || { echo "build-qpdf-native-crypto.sh: install lib failed; skipping" >&2; exit 0; }
sudo cmake --install "$WORK/qpdf/build" --component dev \
  || { echo "build-qpdf-native-crypto.sh: install dev failed; skipping" >&2; exit 0; }
sudo ldconfig 2>/dev/null || true

# Print the new libqpdf's deps for diagnostics — should NOT contain gnutls,
# p11-kit, unistring, or any of the old transitive chain.
NEW_QPDF=$(find /usr/local/lib -maxdepth 1 -name "libqpdf.so.*" -type f 2>/dev/null | head -1)
if [ -n "$NEW_QPDF" ]; then
    echo "Installed: $NEW_QPDF ($(du -h "$NEW_QPDF" | cut -f1))"
    echo "ldd:"
    ldd "$NEW_QPDF" | sed 's/^/  /'
fi

# Drop the apt libqpdf.so so the bundler doesn't pick the old one up. We
# can't `apt-get remove libqpdf-dev` because PDFium-related dev packages
# might depend on it; just move the .so files aside so the linker picks
# /usr/local first.
for old in /usr/lib/x86_64-linux-gnu/libqpdf.so* /usr/lib/aarch64-linux-gnu/libqpdf.so*; do
    [ -e "$old" ] || continue
    sudo mv "$old" "${old}.disabled" 2>/dev/null || true
done
