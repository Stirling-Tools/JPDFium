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

# Portable in-place sed — BSD (macOS) requires a backup suffix argument to -i,
# GNU sed treats it as optional. Using -i.bak works on both; the .bak files are
# removed after each edit. All uses of `sed -i` below go through this wrapper.
sed_i() {
    local file
    local -a sed_args
    # Last positional arg is the file (one per invocation below); everything
    # before it is passed through to sed.
    file="${!#}"
    sed_args=("${@:1:$#-1}")
    sed -i.bak "${sed_args[@]}" "$file"
    rm -f "${file}.bak"
}

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
# PATH ordering matters on Windows: depot_tools ships git.bat shims that
# Python's subprocess.CreateProcess can't resolve (no PATHEXT lookup without
# shell=True). Keep the real Git-for-Windows git.exe ahead of depot_tools'
# git.bat so depot_tools' internal subprocess calls (e.g. git_cache.py
# GetCachePath) succeed. On other OSes, depot_tools should win.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        export PATH="${PATH}:${DEPOT_TOOLS_DIR}"
        # depot_tools tries to fetch Google's bundled VS toolchain by default —
        # not available outside Google, breaks the Windows build.
        export DEPOT_TOOLS_WIN_TOOLCHAIN=0
        ;;
    *)
        export PATH="${DEPOT_TOOLS_DIR}:${PATH}"
        ;;
esac

# Bootstrap depot_tools (downloads bundled Python, cipd, etc.)
(cd "${DEPOT_TOOLS_DIR}" && bash ensure_bootstrap 2>/dev/null || true)

# Prevent depot_tools from auto-updating (speeds up repeated runs)
export DEPOT_TOOLS_UPDATE=0

# depot_tools' git_cache.py shells out to `git` via subprocess.check_output to
# discover the cache path. On Windows the call raises FileNotFoundError when
# Python's CreateProcess can't resolve the binary (common cause: depot_tools'
# git.bat shim is on PATH but not as a literal `git` filename). The upstream
# except clause only catches CalledProcessError, so the script crashes instead
# of falling back to the GIT_CACHE_PATH env var. Widen the catch so the
# fallback works. Harmless on Linux/macOS (the call doesn't raise there).
GIT_CACHE_PY="${DEPOT_TOOLS_DIR}/git_cache.py"
if [ -f "${GIT_CACHE_PY}" ]; then
    # Idempotent: sed replaces the bare except with the widened tuple. Running
    # this twice is a no-op because the second pass won't match. (Previous
    # version of this guard used `! grep -q FileNotFoundError`, which was too
    # broad — that string appears elsewhere in depot_tools and silently
    # skipped the patch.)
    sed_i 's/except subprocess\.CalledProcessError:/except (subprocess.CalledProcessError, FileNotFoundError):/g' "${GIT_CACHE_PY}"
    if grep -q 'except (subprocess.CalledProcessError, FileNotFoundError)' "${GIT_CACHE_PY}"; then
        echo "  Patched depot_tools/git_cache.py to also catch FileNotFoundError."
    else
        echo "  WARNING: depot_tools/git_cache.py did not contain the expected except clause."
    fi
fi
# On Windows, set GIT_CACHE_PATH to empty string. The patched git_cache.py
# returns this as the cachepath, which is falsy — gclient_scm's _GetMirror
# then sees no cache and falls back to direct clones, sidestepping the
# depot_tools git.bat shim which cmd.exe child processes can't resolve
# from bash's exported PATH. On Linux/macOS, use a real cache dir for sync
# performance.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        export GIT_CACHE_PATH=""
        ;;
    *)
        export GIT_CACHE_PATH="${BUILD_DIR}/.gitcache"
        mkdir -p "${GIT_CACHE_PATH}"
        ;;
esac

# ---------- Step 2: gclient checkout ----------
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

if [ ! -f "${BUILD_DIR}/.gclient" ]; then
    echo "[2/7] Configuring gclient for EmbedPDF fork..."
    # download_remoteexec_cfg=False gates three DEPS hooks that fetch
    # remoteexec config from Google's internal CIPD bucket, unreachable from
    # public CI. The buildtools/reclient dep is removed below by patching
    # DEPS directly — custom_deps in .gclient did not take effect for reasons
    # unclear (likely a path-prefix mismatch between gclient versions), so we
    # patch the dep out at the source instead.
    cat > "${BUILD_DIR}/.gclient" <<GCLIENT_EOF
solutions = [
  {
    "name"        : "pdfium",
    "url"         : "${EMBEDPDF_REPO}",
    "managed"     : False,
    "custom_vars" : {
      "download_remoteexec_cfg": False,
    },
  },
]
GCLIENT_EOF
fi

PDFIUM_SRC="${BUILD_DIR}/pdfium"

# Pre-clone the source manually so we can patch DEPS BEFORE gclient processes
# the sub-deps. PDFium's DEPS pins buildtools/reclient at
# infra/rbe/client/<platform>, which has no linux-arm64 CIPD build, so gclient
# sync hard-fails on arm64 runners with "no such package:
# infra/rbe/client/linux-arm64". We don't use remote execution anyway
# (use_remoteexec=false in GN args), so this dep is dead weight on all
# platforms — strip it out before sync.
if [ ! -d "${PDFIUM_SRC}/.git" ]; then
    echo "[2/7] Pre-cloning source so DEPS can be patched..."
    git clone --depth=1 --branch "${EMBEDPDF_BRANCH}" "${EMBEDPDF_REPO}" "${PDFIUM_SRC}"
fi

cd "${PDFIUM_SRC}"

# If EMBEDPDF_PIN_SHA is set (CI prebuild path), hard-check that exact commit so
# every matrix runner builds byte-identical inputs. Otherwise stay on the branch.
if [ -n "${EMBEDPDF_PIN_SHA:-}" ]; then
    echo "  Pinning to SHA ${EMBEDPDF_PIN_SHA}..."
    git fetch --depth=1 origin "${EMBEDPDF_PIN_SHA}"
    git checkout --detach "${EMBEDPDF_PIN_SHA}"
else
    CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
    if [ "${CURRENT_BRANCH}" != "${EMBEDPDF_BRANCH}" ]; then
        echo "  Switching to ${EMBEDPDF_BRANCH}..."
        git checkout "${EMBEDPDF_BRANCH}" 2>/dev/null || git checkout -b "${EMBEDPDF_BRANCH}" "origin/${EMBEDPDF_BRANCH}"
    fi
fi
echo "  Commit: $(git log --oneline -1)"

# Patch DEPS to remove the buildtools/reclient dep. PDFium's DEPS hard-pins
# reclient as an unconditional cipd dep; the platform interpolation
# (infra/rbe/client/${{platform}}) breaks on linux-arm64 because no arm64
# package is published, and we don't use remote execution anyway. We tried
# custom_deps in .gclient (both 'pdfium/buildtools/reclient' and
# 'buildtools/reclient' as keys) but neither took effect for this gclient
# version + this DEPS layout. Patching the DEPS file directly is reliable.
if [ -f DEPS ] && grep -q "'buildtools/reclient'" DEPS; then
    echo "  Patching DEPS to remove buildtools/reclient dep..."
    python3 - DEPS <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    lines = f.readlines()
# Strip the 'buildtools/reclient' dep block. The block contains nested braces
# (a 'packages': [{...}] list), so a regex with [^{}] won't match across them.
# Use a brace-counting scan instead: enter skip mode when we see the dep key
# with a '{', exit when brace depth returns to zero.
out = []
skipping = False
brace_depth = 0
removed = 0
for line in lines:
    if not skipping and "'buildtools/reclient'" in line and ':' in line and '{' in line:
        skipping = True
        brace_depth = line.count('{') - line.count('}')
        continue
    if skipping:
        brace_depth += line.count('{') - line.count('}')
        if brace_depth <= 0:
            skipping = False
            removed += 1
        continue
    out.append(line)
if removed == 0:
    print("  buildtools/reclient block found by grep but brace scan didn't match.")
    sys.exit(0)
with open(path, 'w') as f:
    f.write(''.join(out))
print(f"  Removed {removed} buildtools/reclient dep block from DEPS.")
PY_EOF
fi

# Now run gclient sync. With DEPS patched, cipd ensure won't try to fetch
# infra/rbe/client/linux-arm64. The source is already cloned, so
# --managed=False (set in .gclient) means gclient only processes sub-deps.
cd "${BUILD_DIR}"
if [ ! -d "${PDFIUM_SRC}/third_party/abseil-cpp" ]; then
    echo "[2b/7] Running gclient sync (this downloads ~10 GB of dependencies)..."
    gclient sync --no-history --shallow
else
    if [ "${ACTION}" = "rebuild" ]; then
        echo "[2b/7] Re-syncing sub-deps..."
        gclient sync --no-history --shallow
    else
        echo "[2b/7] Sub-deps already synced."
    fi
fi
cd "${PDFIUM_SRC}"

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

# ---------- Step 4: Patch source for standalone builds ----------
echo "[4/7] Patching source for standalone build..."

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
        sed_i '/visibility += \[ "\/\/third_party:png" \]/a\
  visibility += [ "//fpdfsdk:*" ]' third_party/libpng/visibility.gni
    fi
fi

# Fix null pointer crash in RemoveOrRestoreUnusedResources when a resource
# type is referenced by page objects but has no page-level resource dictionary.
# Also fix null dereference in RecordPageObjectResourceUsage when page objects
# have uninitialized color state (no backing ColorData ref).
CONTENTGEN="core/fpdfapi/edit/cpdf_pagecontentgenerator.cpp"
if [ -f "${CONTENTGEN}" ]; then
    # Patch 1: Guard current_resource_dict->GetKeys() against null
    if grep -q 'const std::vector<ByteString> keys = current_resource_dict->GetKeys();' "${CONTENTGEN}"; then
        echo "  Patching RemoveOrRestoreUnusedResources null dereference..."
        sed_i 's/const std::vector<ByteString> keys = current_resource_dict->GetKeys();/const std::vector<ByteString> keys =\'$'\n''        current_resource_dict ? current_resource_dict->GetKeys()\'$'\n''                              : std::vector<ByteString>();/' "${CONTENTGEN}"
        sed_i 's/RemoveUnusedResources(current_resource_dict, keys,$/current_resource_dict\'$'\n''            ? RemoveUnusedResources(current_resource_dict, keys,/' "${CONTENTGEN}"
        sed_i '/? RemoveUnusedResources(current_resource_dict, keys,/{n;s/resource_in_use_of_current_type);/                                    resource_in_use_of_current_type)\'$'\n''            : CPDF_PageObjectHolder::RemovedResourceMap();/;}' "${CONTENTGEN}"
    fi

    # Patch 2: Guard color_state() accessor calls against uninitialized ref
    if grep -q 'if (!cs.GetFillColorSpaceResName().IsEmpty())' "${CONTENTGEN}" && \
       ! grep -q 'if (cs.HasRef())' "${CONTENTGEN}"; then
        echo "  Patching RecordPageObjectResourceUsage color state null dereference..."
        sed_i '/const CPDF_ColorState& cs = page_object->color_state();/{n;s/if (!cs.GetFillColorSpaceResName/if (cs.HasRef()) {\'$'\n''    if (!cs.GetFillColorSpaceResName/;}' "${CONTENTGEN}"
        sed_i '/cs.GetStrokePatternResName());/{n;s/^}/  }\'$'\n''}/;}' "${CONTENTGEN}" 2>/dev/null || true
    fi
fi

# ---------- Step 5: GN configuration ----------
echo "[5/7] Configuring GN build..."
OUT_DIR="out/Release"

# Two build flavors selected by ${JPDFIUM_BUILD_MODE:-component}:
#
#   "component" (default, used by snapshot.yml / publish-github-packages.yml /
#       ci.yml — iteration-friendly path):
#         is_component_build=true → SOLINK each PDFium subcomponent into its
#         own .so. Output: libpdfium.so + ~15 sibling .so files (abseil,
#         partition_alloc, libchrome_zlib, icuuc, libpng, freetype, …) that
#         the bridge links against at the consumer end. Build is fast and
#         per-component, debugging is easier.
#
#   "static" (release-only, used by release.yml — release-size-optimized
#       path):
#         is_component_build=false + pdf_is_complete_lib=true → roll all of
#         PDFium and its bundled deps (abseil, partition_alloc, zlib, libpng,
#         freetype, harfbuzz, icu, etc.) into a single libpdfium.a static
#         archive. Bridge then links it into libjpdfium.so with
#         -Wl,--whole-archive so the LTO can dead-strip cross-module unused
#         code, dropping ~16 MB across the 5-platform natives bundle vs
#         component mode.
#
# Both modes share the rest of the args:
#   use_allocator_shim=false: without this, PartitionAlloc replaces the
#       system allocator (malloc/free), which crashes when loaded into a
#       JVM that already manages its own heap.
#   COMPONENT_BUILD + FPDF_IMPLEMENTATION defines (set automatically by
#   is_component_build=true) give FPDF_EXPORT symbols
#   visibility("default") — in static mode we instead pass these via
#   compile flags below.
JPDFIUM_BUILD_MODE="${JPDFIUM_BUILD_MODE:-component}"
case "$JPDFIUM_BUILD_MODE" in
    component)
        GN_ARGS='is_debug=false is_component_build=true pdf_is_standalone=true pdf_enable_v8=false pdf_enable_xfa=false use_remoteexec=false clang_use_chrome_plugins=false treat_warnings_as_errors=false symbol_level=0 use_sysroot=false use_custom_libcxx=false use_allocator_shim=false'
        ;;
    static)
        GN_ARGS='is_debug=false is_component_build=false pdf_is_complete_lib=true pdf_is_standalone=true pdf_enable_v8=false pdf_enable_xfa=false use_remoteexec=false clang_use_chrome_plugins=false treat_warnings_as_errors=false symbol_level=0 use_sysroot=false use_custom_libcxx=false use_allocator_shim=false'
        ;;
    *)
        echo "ERROR: unknown JPDFIUM_BUILD_MODE=$JPDFIUM_BUILD_MODE (expected: component, static)" >&2
        exit 1
        ;;
esac
echo "  Build mode : ${JPDFIUM_BUILD_MODE}"

# Honor TARGET_CPU for cross-compile builds (e.g. darwin-x64 from macos-14
# arm64 runner, or linux-arm64 from ubuntu-latest x64 runner). Defaults to
# host CPU when unset.
if [ -n "${TARGET_CPU:-}" ]; then
    GN_ARGS="${GN_ARGS} target_cpu=\"${TARGET_CPU}\""
    echo "  target_cpu: ${TARGET_CPU}"
fi

# For Linux arm64 cross-compile from x64 host, the bundled Chromium clang
# acts as a cross-compiler (--target=aarch64-linux-gnu), but it needs a
# matching sysroot for arm64 system libraries. Toggle use_sysroot=true and
# explicitly invoke install-sysroot.py — gclient sync's hooks don't pull
# the arm64 sysroot unless target_cpu is in the .gclient (it's only in
# GN args here), so we trigger it manually. Also force use_custom_libcxx=true
# because the cross-compile build can't find libc++ module headers (e.g.
# `__configuration/abi.h`) when using the system libc++ search paths. Native
# linux-x64 keeps use_sysroot=false / use_custom_libcxx=false (system libs).
if [ "$(uname -s)" = "Linux" ] && [ "${TARGET_CPU:-}" = "arm64" ]; then
    GN_ARGS="${GN_ARGS/use_sysroot=false/use_sysroot=true}"
    GN_ARGS="${GN_ARGS/use_custom_libcxx=false/use_custom_libcxx=true}"
    # Disable AArch64 Branch Target Identification. The default Chromium
    # arm64 link adds `-Wl,-z,force-bti`, which requires every input object
    # to have the GNU_PROPERTY_AARCH64_FEATURE_1_BTI marker. The Debian
    # Bullseye arm64 sysroot's CRT objects (crti.o, crtn.o, crtbeginS.o,
    # crtendS.o) were compiled before BTI was widely adopted and lack that
    # property, so SOLINK of libc++.so fails. Disabling BTI matches what
    # bblanchon/pdfium-binaries does for arm64.
    GN_ARGS="${GN_ARGS} arm_control_flow_integrity=\"none\""
    echo "  Linux arm64 cross-compile: use_sysroot=true, use_custom_libcxx=true, BTI off"
    if [ -f build/linux/sysroot_scripts/install-sysroot.py ]; then
        echo "  Installing arm64 sysroot..."
        python3 build/linux/sysroot_scripts/install-sysroot.py --arch=arm64
    else
        echo "  WARNING: install-sysroot.py not found at expected path."
    fi
fi

# Windows has no system libc++, so use_custom_libcxx=false produces broken
# component builds — abseil-cpp.dll fails to link with undefined symbols on
# std::__Cr::basic_string template instantiations because the libc++ DLL
# doesn't export them when built in "use system libc++" mode. Force
# use_custom_libcxx=true so Chromium builds and exports its own libc++.
# Also initialize depot_tools' python3 wrapper (writes python3_bin_reldir.txt)
# which gn needs before it can call any python3 build step.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        GN_ARGS="${GN_ARGS/use_custom_libcxx=false/use_custom_libcxx=true}"
        echo "  Windows: use_custom_libcxx=true"
        echo "  Initializing depot_tools python3 wrapper on Windows..."
        DEPOT_TOOLS_UPDATE=1 gclient --version 2>&1 || true
        ;;
esac

gn gen "${OUT_DIR}" --args="${GN_ARGS}"

# ---------- Step 6: Build ----------
echo "[6/7] Building PDFium (this may take 15-60 minutes)..."
NPROC="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
ninja -C "${OUT_DIR}" pdfium -j "${NPROC}"

# ---------- Step 7: Install ----------
echo "[7/7] Installing to ${TARGET_DIR}..."
mkdir -p "${TARGET_DIR}/include" "${TARGET_DIR}/lib"

# Output filenames + extensions differ by OS AND by build mode:
#
#   component mode: a SOLINK'd shared library (libpdfium.so / .dylib /
#       pdfium.dll) plus per-component sibling shared libs.
#   static mode:    a single static archive (libpdfium.a on POSIX,
#       pdfium.lib on Windows) containing PDFium + every bundled dep.
case "$(uname -s)" in
    Linux*)
        LIB_EXT="so"
        STATIC_EXT="a"
        MAIN_LIB_COMPONENT="libpdfium.so"
        MAIN_LIB_STATIC="libpdfium.a"
        ;;
    Darwin*)
        LIB_EXT="dylib"
        STATIC_EXT="a"
        MAIN_LIB_COMPONENT="libpdfium.dylib"
        MAIN_LIB_STATIC="libpdfium.a"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        LIB_EXT="dll"
        STATIC_EXT="lib"
        MAIN_LIB_COMPONENT="pdfium.dll"
        MAIN_LIB_STATIC="pdfium.lib"
        ;;
    *)
        echo "ERROR: Unsupported OS for install step: $(uname -s)" >&2
        exit 1
        ;;
esac

if [ "$JPDFIUM_BUILD_MODE" = "static" ]; then
    MAIN_LIB="$MAIN_LIB_STATIC"
    # gn places static archives under out/Release/obj/<target>.<ext> on
    # POSIX targets and out/Release/<target>.<ext> on Windows. Find the
    # archive wherever ninja put it.
    SRC=$(find "${OUT_DIR}" -maxdepth 3 -type f -name "${MAIN_LIB}" 2>/dev/null | head -1)
    if [ -z "$SRC" ] || [ ! -f "$SRC" ]; then
        echo "  ERROR: ${MAIN_LIB} not produced — check pdf_is_complete_lib args" >&2
        exit 1
    fi
    cp "$SRC" "${TARGET_DIR}/lib/${MAIN_LIB}"
    LIB_COUNT=1
    echo "  Copied static archive: ${SRC} ($(du -h "$SRC" | cut -f1)) -> ${TARGET_DIR}/lib/"
else
    MAIN_LIB="$MAIN_LIB_COMPONENT"
    # Component build: copy all shared libraries from the output directory.
    if [ "${LIB_EXT}" = "dll" ]; then
        # Windows: pdfium.dll plus dependency DLLs and their import libraries.
        cp "${OUT_DIR}"/*.dll "${TARGET_DIR}/lib/"
        cp "${OUT_DIR}"/*.dll.lib "${TARGET_DIR}/lib/" 2>/dev/null || true
        LIB_COUNT="$(ls -1 "${TARGET_DIR}/lib/"*.dll 2>/dev/null | wc -l)"
    else
        cp "${OUT_DIR}"/lib*."${LIB_EXT}" "${TARGET_DIR}/lib/"
        LIB_COUNT="$(ls -1 "${TARGET_DIR}/lib/"lib*."${LIB_EXT}" 2>/dev/null | wc -l)"
    fi
    echo "  Copied ${LIB_COUNT} shared libraries -> ${TARGET_DIR}/lib/"
fi

# Copy public headers
cp public/*.h "${TARGET_DIR}/include/"
HEADER_COUNT="$(ls -1 public/*.h | wc -l)"
echo "  Copied ${HEADER_COUNT} headers -> ${TARGET_DIR}/include/"

MAIN_LIB_PATH="${TARGET_DIR}/lib/${MAIN_LIB}"
if [ ! -f "${MAIN_LIB_PATH}" ]; then
    echo "  ERROR: Main library not found at ${MAIN_LIB_PATH}" >&2
    exit 1
fi
echo "  Main library: ${MAIN_LIB_PATH}"

# Verify EPDF symbols are exported. nm flags differ by OS AND by whether
# we built a .so/.dylib (dynamic symbols, -D / -gU) or a .a (defined
# symbols, plain -g).
case "$(uname -s)" in
    Linux*)
        if [ "$JPDFIUM_BUILD_MODE" = "static" ]; then
            NM_FLAGS="-g --defined-only"
        else
            NM_FLAGS="-D"
        fi
        EPDF_COUNT="$(nm ${NM_FLAGS} "${MAIN_LIB_PATH}" 2>/dev/null | grep -c ' T.*EPDF' || true)"
        FPDF_COUNT="$(nm ${NM_FLAGS} "${MAIN_LIB_PATH}" 2>/dev/null | grep -c ' T.*FPDF' || true)"
        ;;
    Darwin*)
        if [ "$JPDFIUM_BUILD_MODE" = "static" ]; then
            NM_FLAGS="-gU"   # works for .a too
        else
            NM_FLAGS="-gU"
        fi
        EPDF_COUNT="$(nm ${NM_FLAGS} "${MAIN_LIB_PATH}" 2>/dev/null | grep -c ' T _\{0,1\}EPDF' || true)"
        FPDF_COUNT="$(nm ${NM_FLAGS} "${MAIN_LIB_PATH}" 2>/dev/null | grep -c ' T _\{0,1\}FPDF' || true)"
        ;;
    *)
        EPDF_COUNT="(skipped on $(uname -s))"
        FPDF_COUNT="(skipped on $(uname -s))"
        ;;
esac
echo ""
echo "  Symbol verification:"
echo "    EPDF_* symbols: ${EPDF_COUNT}"
echo "    FPDF_* symbols: ${FPDF_COUNT}"

if [ "${EPDF_COUNT}" = "0" ]; then
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
