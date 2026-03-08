#!/usr/bin/env bash
# Builds PDFium from the EmbedPDF fork source into native/pdfium/
#
# The EmbedPDF fork provides enhanced APIs: native redaction, encryption,
# annotation rotation/appearance, page-rotation normalization, and more.
# Source: https://github.com/embedpdf/pdfium  (branch: embedpdf/main)
#
# Usage:
#   ./native/setup-pdfium.sh             # First-time build (clones + builds)
#   ./native/setup-pdfium.sh --rebuild   # Force rebuild (keeps source checkout)
#   ./native/setup-pdfium.sh --clean     # Full clean rebuild (removes everything)
#
# Prerequisites:
#   - git, python3, ninja-build (or ninja)
#   - ~15 GB disk space (source checkout + build artifacts)
#   - Build takes 15-60 minutes depending on hardware
#
# The script will install depot_tools (gclient/gn/ninja) automatically.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="${SCRIPT_DIR}/pdfium"
BUILD_DIR="${SCRIPT_DIR}/pdfium-src"
DEPOT_TOOLS_DIR="${BUILD_DIR}/depot_tools"
EMBEDPDF_REPO="https://github.com/embedpdf/pdfium.git"
EMBEDPDF_BRANCH="embedpdf/main"

# ---------- argument handling ----------
ACTION="build"
case "${1:-}" in
    --clean)   ACTION="clean" ;;
    --rebuild) ACTION="rebuild" ;;
    --help|-h)
        echo "Usage: $0 [--rebuild|--clean|--help]"
        exit 0 ;;
esac

if [ "${ACTION}" = "clean" ]; then
    echo "Cleaning everything..."
    rm -rf "${TARGET_DIR}/lib" "${TARGET_DIR}/include"
    rm -rf "${BUILD_DIR}"
fi

if [ -d "${TARGET_DIR}/lib" ] && [ -d "${TARGET_DIR}/include" ] && [ "${ACTION}" = "build" ]; then
    echo "PDFium already present at ${TARGET_DIR}."
    echo "  Pass --rebuild to force rebuild, or --clean for full clean."
    exit 0
fi

# ---------- prerequisite checks ----------
for cmd in git python3; do
    if ! command -v "${cmd}" &>/dev/null; then
        echo "ERROR: '${cmd}' is required but not found." >&2
        exit 1
    fi
done

echo "=============================================="
echo " Building PDFium from EmbedPDF fork source"
echo "=============================================="
echo "  Source  : ${EMBEDPDF_REPO} (${EMBEDPDF_BRANCH})"
echo "  Build   : ${BUILD_DIR}"
echo "  Install : ${TARGET_DIR}/{include,lib}"
echo ""

# ---------- Step 1: depot_tools ----------
if [ ! -d "${DEPOT_TOOLS_DIR}" ]; then
    echo "[1/7] Installing depot_tools..."
    git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git "${DEPOT_TOOLS_DIR}"
else
    echo "[1/7] depot_tools already present."
fi
export PATH="${DEPOT_TOOLS_DIR}:${PATH}"

# Bootstrap depot_tools (downloads bundled Python, cipd, etc.)
(cd "${DEPOT_TOOLS_DIR}" && bash ensure_bootstrap 2>/dev/null || true)

# Prevent depot_tools from auto-updating (speeds up repeated runs)
export DEPOT_TOOLS_UPDATE=0

# ---------- Step 2: gclient checkout ----------
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

if [ ! -f "${BUILD_DIR}/.gclient" ]; then
    echo "[2/7] Configuring gclient for EmbedPDF fork..."
    gclient config --unmanaged "${EMBEDPDF_REPO}"
fi

PDFIUM_SRC="${BUILD_DIR}/pdfium"

if [ ! -d "${PDFIUM_SRC}/.git" ]; then
    echo "[2/7] Running gclient sync (this downloads ~10 GB of dependencies)..."
    gclient sync --no-history --shallow
else
    if [ "${ACTION}" = "rebuild" ]; then
        echo "[2/7] Updating source..."
        cd "${PDFIUM_SRC}"
        git fetch origin "${EMBEDPDF_BRANCH}"
        git checkout "origin/${EMBEDPDF_BRANCH}"
        cd "${BUILD_DIR}"
        gclient sync --no-history --shallow
    else
        echo "[2/7] Source already checked out."
    fi
fi

cd "${PDFIUM_SRC}"

# Ensure we are on the EmbedPDF branch
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
if [ "${CURRENT_BRANCH}" != "${EMBEDPDF_BRANCH}" ]; then
    echo "  Switching to ${EMBEDPDF_BRANCH}..."
    git checkout "${EMBEDPDF_BRANCH}" 2>/dev/null || git checkout -b "${EMBEDPDF_BRANCH}" "origin/${EMBEDPDF_BRANCH}"
fi
echo "  Commit: $(git log --oneline -1)"

# ---------- Step 3: Install Linux build deps ----------
if [ "$(uname -s)" = "Linux" ]; then
    echo "[3/7] Checking Linux build dependencies..."
    if [ -f build/install-build-deps.sh ]; then
        if command -v apt-get &>/dev/null; then
            echo "  Running install-build-deps.sh (may require sudo)..."
            ./build/install-build-deps.sh --no-prompt --no-chromeos-fonts || true
        else
            echo "  Non-Debian system detected. Ensure clang, lld, pkg-config, ninja-build are installed."
            echo "  Fedora: sudo dnf install clang lld pkg-config ninja-build"
        fi
    fi
else
    echo "[3/7] Not Linux, skipping build deps install."
fi

# ---------- Step 4: Patch source for shared-library export ----------
echo "[4/7] Patching source for shared library build..."

# The upstream FPDF_EXPORT macro only uses visibility("default") when
# COMPONENT_BUILD is defined, but standalone PDFium builds cannot use
# is_component_build=true (it requires the full Chromium //base).
# We patch the header to always export FPDF_EXPORT symbols so that the
# manually-linked shared library exposes all FPDF_*/EPDF_* symbols.
if grep -q 'defined(COMPONENT_BUILD)' public/fpdfview.h; then
    python3 - public/fpdfview.h << 'PYEOF'
import sys, re
path = sys.argv[1]
with open(path) as f:
    text = f.read()
old_block = re.compile(
    r'#if defined\(COMPONENT_BUILD\)\n.*?#endif  // defined\(COMPONENT_BUILD\)\n',
    re.DOTALL
)
new_block = (
    '#if defined(WIN32)\n'
    '#define FPDF_EXPORT __declspec(dllexport)\n'
    '#else\n'
    '#define FPDF_EXPORT __attribute__((visibility("default")))\n'
    '#endif\n'
)
text = old_block.sub(new_block, text, count=1)
with open(path, 'w') as f:
    f.write(text)
print("  Patched FPDF_EXPORT in fpdfview.h")
PYEOF
else
    echo "  FPDF_EXPORT already patched."
fi

# Create stub base/BUILD.gn if missing (standalone builds lack full //base)
if [ ! -f base/BUILD.gn ] || ! grep -q 'group("base")' base/BUILD.gn 2>/dev/null; then
    echo "  Creating stub base/BUILD.gn..."
    mkdir -p base/test
    echo 'group("base") {}' > base/BUILD.gn
    printf 'group("run_all_unittests") {}\ngroup("test_support") {}\n' > base/test/BUILD.gn
fi

# Fix libpng visibility for fpdfsdk dependency (EmbedPDF adds PNG support)
if [ -f third_party/libpng/visibility.gni ]; then
    if ! grep -q 'fpdfsdk' third_party/libpng/visibility.gni; then
        echo "  Adding fpdfsdk to libpng visibility..."
        sed -i '/visibility += \[ "\/\/third_party:png" \]/a\  visibility += [ "//fpdfsdk:*" ]' \
            third_party/libpng/visibility.gni
    fi
fi

# ---------- Step 5: GN configuration ----------
echo "[5/7] Configuring GN build..."
OUT_DIR="out/Release"

# Static build (is_component_build=false) because standalone PDFium
# cannot use component build (requires full Chromium //base).
# Symbol export is handled by the FPDF_EXPORT patch + version script.
GN_ARGS='is_debug=false is_component_build=false pdf_is_standalone=true pdf_enable_v8=false pdf_enable_xfa=false use_remoteexec=false clang_use_chrome_plugins=false treat_warnings_as_errors=false symbol_level=0 use_sysroot=false use_custom_libcxx=false'

gn gen "${OUT_DIR}" --args="${GN_ARGS}"

# ---------- Step 6: Build ----------
echo "[6/7] Building PDFium (this may take 15-60 minutes)..."
NPROC="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
ninja -C "${OUT_DIR}" pdfium -j "${NPROC}"

# ---------- Step 7: Link shared library + install ----------
echo "[7/7] Linking shared library and installing to ${TARGET_DIR}..."
mkdir -p "${TARGET_DIR}/include" "${TARGET_DIR}/lib"

# Detect clang from the build toolchain (depot_tools ships its own)
CLANG="${PDFIUM_SRC}/third_party/llvm-build/Release+Asserts/bin/clang++"
if [ ! -f "${CLANG}" ]; then
    CLANG="clang++"
fi

# Create version script to export only public API symbols
VERSION_SCRIPT="$(mktemp)"
cat > "${VERSION_SCRIPT}" << 'VEOF'
{
  global:
    FPDF*;
    EPDF*;
    FORM_*;
    FSDK_*;
  local:
    *;
};
VEOF

# Link all object files + archives into a single shared library.
# Archives contain third-party code; standalone .o files contain PDFium core.
# --allow-multiple-definition handles overlap between .a and .o files.
"${CLANG}" -shared -fuse-ld=lld \
    -o "${OUT_DIR}/libpdfium.so" \
    -Wl,--whole-archive $(find "${OUT_DIR}/obj" -name "*.a" | sort | tr '\n' ' ') -Wl,--no-whole-archive \
    $(find "${OUT_DIR}/obj" -name "*.o" | sort | tr '\n' ' ') \
    -Wl,--allow-multiple-definition \
    -Wl,--version-script="${VERSION_SCRIPT}" \
    -lpthread -lm -ldl

rm -f "${VERSION_SCRIPT}"

# Install
cp "${OUT_DIR}/libpdfium.so" "${TARGET_DIR}/lib/"
echo "  Copied libpdfium.so -> ${TARGET_DIR}/lib/"

# Copy public headers
cp public/*.h "${TARGET_DIR}/include/"
HEADER_COUNT="$(ls -1 public/*.h | wc -l)"
echo "  Copied ${HEADER_COUNT} headers -> ${TARGET_DIR}/include/"

# Verify EPDF symbols are exported
EPDF_COUNT="$(nm -D "${TARGET_DIR}/lib/libpdfium.so" 2>/dev/null | grep -c ' T.*EPDF' || true)"
FPDF_COUNT="$(nm -D "${TARGET_DIR}/lib/libpdfium.so" 2>/dev/null | grep -c ' T.*FPDF' || true)"
echo ""
echo "  Symbol verification:"
echo "    EPDF_* symbols: ${EPDF_COUNT}"
echo "    FPDF_* symbols: ${FPDF_COUNT}"

if [ "${EPDF_COUNT}" -eq 0 ]; then
    echo ""
    echo "  WARNING: No EPDF_* symbols found! The build may not include EmbedPDF extensions."
    echo "  Make sure you are on the embedpdf/main branch."
fi

echo ""
echo "=============================================="
echo " PDFium build complete!"
echo "=============================================="
echo "  Headers : ${TARGET_DIR}/include/"
echo "  Library : ${TARGET_DIR}/lib/"
echo ""
echo "Next: bash native/build-real.sh"
