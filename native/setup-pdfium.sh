#!/usr/bin/env bash
# Builds PDFium from source into native/pdfium/.
set -euo pipefail

sed_i() {
    local file
    local -a sed_args
    file="${!#}"
    sed_args=("${@:1:$#-1}")
    sed -i.bak "${sed_args[@]}" "$file"
    rm -f "${file}.bak"
}

# GitHub answers git traffic with HTTP 429 when the whole prebuild matrix clones
# at once, and other transient RPC/network failures happen too. Retry a few
# times before surfacing the failure.
git_retry() {
    local attempt
    for attempt in 1 2 3; do
        if "$@"; then
            return 0
        fi
        echo "  git: '$*' failed (attempt ${attempt}/3), retrying in 15s..." >&2
        sleep 15
    done
    return 1
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="${SCRIPT_DIR}/pdfium"
BUILD_DIR="${SCRIPT_DIR}/pdfium-src"
DEPOT_TOOLS_DIR="${BUILD_DIR}/depot_tools"
EMBEDPDF_REPO="https://github.com/embedpdf/pdfium.git"
EMBEDPDF_BRANCH="embedpdf/main"

# Authenticate github.com HTTPS clones with the workflow token when one is
# available: unauthenticated git requests get rate limited (HTTP 429) once the
# ~18 prebuild matrix runners all hit github.com at the same time. Silently
# skipped for local runs without a token.
if [ -n "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ]; then
    git config --global credential.helper store
    printf 'https://x-access-token:%s@github.com\n' "${GH_TOKEN:-${GITHUB_TOKEN}}" > "${HOME}/.git-credentials"
    chmod 600 "${HOME}/.git-credentials"
    echo "  git: authenticating github.com clones with the workflow token"
fi

# ---------- musl / Alpine libc adjustments (must run before gclient sync) ----------
# depot_tools' vpython wrapper downloads a GLIBC-built CPython and builds a venv
# from it. That binary cannot run on musl even under gcompat - it imports
# glibc-only symbols (preadv64v2 / pwritev64v2 / posix_fallocate64) which musl
# does not export, so gclient sync dies with "Error relocating ... python3:
# <symbol>: symbol not found". VPYTHON_BYPASS makes the wrapper exec the system
# python3 (Alpine's musl build) instead. This must be exported BEFORE any
# gclient invocation.
if [ "${JPDFIUM_LIBC:-}" = "musl" ]; then
    export VPYTHON_BYPASS="manually managed python not supported by chrome operations"
    echo "  musl/Alpine: VPYTHON_BYPASS set - gclient will use the system python3"
    # NOTE: the gsutil that depot_tools downloads (5.35) refuses to run on
    # Python 3.14, which is what Alpine 3.24 ships. The version gate lives inside
    # that downloaded zip, not in depot_tools, so it cannot be patched here;
    # native/ci/alpine-exec.sh pins the container to Alpine 3.23 (Python 3.12)
    # instead.
fi

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
    rm -rf "${TARGET_DIR:?}/lib" "${TARGET_DIR:?}/include"
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
        # depot_tools tries to fetch Google's bundled VS toolchain by default -
        # not available outside Google, breaks the Windows build.
        export DEPOT_TOOLS_WIN_TOOLCHAIN=0
        ;;
    *)
        export PATH="${DEPOT_TOOLS_DIR}:${PATH}"
        ;;
esac

# Bootstrap depot_tools (downloads bundled Python, cipd, etc.)
(cd "${DEPOT_TOOLS_DIR}" && bash ensure_bootstrap 2>/dev/null || true)

# depot_tools' `python3` shim (python-bin/python3) refuses to run until
# ${DEPOT_TOOLS_DIR}/python3_bin_reldir.txt names the directory holding the
# bundled CPython; otherwise it aborts with
#   python3_bin_reldir.txt not found. need to initialize depot_tools by
#   running gclient, update_depot_tools or ensure_bootstrap.
# ensure_bootstrap cannot produce that file on musl: the CPython it downloads
# links against glibc and cannot execute on Alpine, so the file is never
# written and `gn gen` (which goes through this shim) dies. Point the shim at
# the interpreter the rest of the build already uses - the musl branch exports
# VPYTHON_BYPASS for the same reason, so gclient sync already runs on the
# system python3.
if [ ! -f "${DEPOT_TOOLS_DIR}/python3_bin_reldir.txt" ]; then
    SYSTEM_PYTHON3="$(command -v python3 || true)"
    if [ -n "${SYSTEM_PYTHON3}" ]; then
        echo "  ensure_bootstrap produced no usable CPython; wiring depot_tools' python3 shim to ${SYSTEM_PYTHON3}..."
        mkdir -p "${DEPOT_TOOLS_DIR}/python3_bin_alpine"
        ln -sf "${SYSTEM_PYTHON3}" "${DEPOT_TOOLS_DIR}/python3_bin_alpine/python3"
        printf 'python3_bin_alpine\n' > "${DEPOT_TOOLS_DIR}/python3_bin_reldir.txt"
        # ensure_bootstrap also failed to install the CIPD vpython3 package, so
        # vpython3 would abort with ".../.cipd_bin/vpython3: No such file or
        # directory" (seen after a transient HTTP 403 from
        # chrome-infra-packages.appspot.com) and take gclient sync down with it.
        # VPYTHON_BYPASS makes the wrapper exec the system python3 directly, so a
        # CIPD outage can no longer fail the row.
        export VPYTHON_BYPASS="manually managed python not supported by chrome operations"
        if [ -n "${GITHUB_ENV:-}" ]; then
            printf 'VPYTHON_BYPASS=%s\n' "${VPYTHON_BYPASS}" >> "${GITHUB_ENV}"
        fi
    fi
fi

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
    # broad - that string appears elsewhere in depot_tools and silently
    # skipped the patch.)
    sed_i 's/except subprocess\.CalledProcessError:/except (subprocess.CalledProcessError, FileNotFoundError):/g' "${GIT_CACHE_PY}"
    if grep -q 'except (subprocess.CalledProcessError, FileNotFoundError)' "${GIT_CACHE_PY}"; then
        echo "  Patched depot_tools/git_cache.py to also catch FileNotFoundError."
    else
        echo "  WARNING: depot_tools/git_cache.py did not contain the expected except clause."
    fi
fi
# On Windows, set GIT_CACHE_PATH to empty string. The patched git_cache.py
# returns this as the cachepath, which is falsy - gclient_scm's _GetMirror
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
    # DEPS directly - custom_deps in .gclient did not take effect for reasons
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
# platforms - strip it out before sync.
if [ ! -d "${PDFIUM_SRC}/.git" ]; then
    echo "[2/7] Pre-cloning source so DEPS can be patched..."
    if ! git_retry git clone --depth=1 --branch "${EMBEDPDF_BRANCH}" "${EMBEDPDF_REPO}" "${PDFIUM_SRC}"; then
        # A half-finished clone would make the retry fail on "already exists".
        rm -rf "${PDFIUM_SRC}"
        git_retry git clone --depth=1 --branch "${EMBEDPDF_BRANCH}" "${EMBEDPDF_REPO}" "${PDFIUM_SRC}"
    fi
fi

cd "${PDFIUM_SRC}"

# If EMBEDPDF_PIN_SHA is set (CI prebuild path), hard-check that exact commit so
# every matrix runner builds byte-identical inputs. Otherwise stay on the branch.
if [ -n "${EMBEDPDF_PIN_SHA:-}" ]; then
    echo "  Pinning to SHA ${EMBEDPDF_PIN_SHA}..."
    git_retry git fetch --depth=1 origin "${EMBEDPDF_PIN_SHA}"
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

# EmbedPDF annot font subsetting mixes two span pointer types in a ternary.
# clang rejects it when pdf_use_partition_alloc=true. Use if/else so each
# branch converts to the declared span type on its own.
ANNOT_SUBSET="core/fpdfdoc/cpdf_annotfontsubset.cpp"
if [ -f "${ANNOT_SUBSET}" ]; then
    if grep -q 'pdfium::span<const uint8_t> font_data = subset_font_data.empty()' "${ANNOT_SUBSET}"; then
        echo "  Patching cpdf_annotfontsubset.cpp ambiguous span conditional..."
        python3 - "${ANNOT_SUBSET}" <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    src = f.read()
old = """  pdfium::span<const uint8_t> font_data = subset_font_data.empty()
                                              ? font->GetFontSpan()
                                              : pdfium::span(subset_font_data);"""
new = """  pdfium::span<const uint8_t> font_data;
  if (subset_font_data.empty()) {
    font_data = font->GetFontSpan();
  } else {
    font_data = pdfium::span<const uint8_t>(subset_font_data);
  }"""
if old not in src:
    print("  WARNING: expected ternary not found in cpdf_annotfontsubset.cpp - skipping")
    sys.exit(0)
with open(path, 'w') as f:
    f.write(src.replace(old, new, 1))
print("  Rewrote cpdf_annotfontsubset.cpp font_data ternary as if/else.")
PY_EOF
    fi
fi

# Fix output library name in BUILD.gn to ensure standard libpdfium.* / pdfium.* naming
if [ -f BUILD.gn ] && grep -q 'output_name = "embedpdf"' BUILD.gn; then
    echo "  Patching BUILD.gn output_name to pdfium..."
    sed_i 's/output_name = "embedpdf"/output_name = "pdfium"/g' BUILD.gn
fi

# Apply Skia geometry-only-stroke patch if present (needed for cpdf_path_redactor / SkStrokeRec)
if [ -d third_party/skia ] && [ -f patches/embedpdf-runtime/skia/geometry-only-stroke.patch ]; then
    echo "  Applying Skia geometry-only-stroke patch..."
    (cd third_party/skia && patch --forward --batch -p1 < ../../patches/embedpdf-runtime/skia/geometry-only-stroke.patch 2>/dev/null || true)
fi

# On Windows component builds without full Skia (pdf_use_skia=false), skia_config must not define SKIA_DLL,
# and SkPdfiumUserConfig.h must not define SK_API as COMPONENT_EXPORT(SKIA). Otherwise SkPathBuilder/SkStrokeRec
# symbols get marked __declspec(dllimport) while being statically linked into pdfium.dll, causing lld-link LNK4217.
#
# The gate below must key off SKIA_DLL, never off PDF_USE_SKIA. PDF_USE_SKIA is only added by
# pdfium_common_config to PDFium's own targets, so Skia's own translation units (skia_core_and_effects, the
# skia component) never see it. Gating on it left Skia's definitions with an empty SK_API, which - combined
# with Chromium's hidden default visibility for component builds - made libskia.dylib/.so/.dll link but export
# nothing. Every component row then failed linking libpdfium with hundreds of undefined Skia symbols
# (SkSurface::getCanvas, SkPaint::SkPaint, SkImageInfo::Make, ...). skia_config defines SKIA_DLL for both
# Skia's own targets and its consumers exactly when Skia is a shared library (is_component_build &&
if [ -f skia/BUILD.gn ] && grep -q 'if (is_component_build) {' skia/BUILD.gn; then
    echo "  Patching skia/BUILD.gn to gate SKIA_DLL on pdf_use_skia..."
    sed_i 's/if (is_component_build) {/if (is_component_build \&\& pdf_use_skia) {/g' skia/BUILD.gn
fi
# When pdf_use_skia=true in component mode, component("skia") compiles Skia as a shared library
# (libskia.so / libskia.dylib / skia.dll). cpdf_path_redactor.cpp depends on SkDashPath, which lives
# in skia_utils_private. Upstream SkDashPathPriv.h does NOT decorate the SkDashPath namespace functions
# with SK_API, so libskia exports nothing from SkDashPath under hidden visibility, failing the libpdfium
# SOLINK with undefined symbol: SkDashPath::ValidDashPath / CalcDashParameters / InternalFilter.
# Export SkDashPath functions via SK_API in SkDashPathPriv.h if not already annotated.
SK_DASH_PRIV="third_party/skia/src/utils/SkDashPathPriv.h"
if [ -f "${SK_DASH_PRIV}" ] && ! grep -q 'SK_API.*ValidDashPath' "${SK_DASH_PRIV}"; then
    echo "  Patching SkDashPathPriv.h to annotate SkDashPath with SK_API..."
    python3 - "${SK_DASH_PRIV}" <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    src = f.read()
replacements = [
    ("void CalcDashParameters(", "SK_API void CalcDashParameters("),
    ("bool FilterDashPath(", "SK_API bool FilterDashPath("),
    ("bool InternalFilter(", "SK_API bool InternalFilter("),
    ("bool ValidDashPath(", "SK_API bool ValidDashPath("),
]
for old, new in replacements:
    if old in src and new not in src:
        src = src.replace(old, new, 1)
with open(path, 'w') as f:
    f.write(src)
print("  Annotated SkDashPath functions with SK_API.")
PY_EOF
fi
SK_USER_CONFIG="skia/config/SkPdfiumUserConfig.h"
if [ -f "${SK_USER_CONFIG}" ] && grep -q '#define SK_API COMPONENT_EXPORT(SKIA)' "${SK_USER_CONFIG}"; then
    echo "  Patching SkPdfiumUserConfig.h to gate SK_API export on SKIA_DLL..."
    python3 - "${SK_USER_CONFIG}" <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    src = f.read()
target = "#define SK_API COMPONENT_EXPORT(SKIA)"
replacement = """#if defined(SKIA_DLL)
#define SK_API COMPONENT_EXPORT(SKIA)
#else
#define SK_API
#endif"""
if target in src:
    with open(path, 'w') as f:
        f.write(src.replace(target, replacement, 1))
    print("  Rewrote SK_API in SkPdfiumUserConfig.h.")
PY_EOF
fi

# The EmbedPDF fork's fpdfsdk/ tree lags its own core/fxge: it still calls the
# pre-rename CFX_GEModule::Get()->UseSkiaRenderer(), while core/fxge/cfx_gemodule.h
# only declares the static CFX_GEModule::IsSkiaRenderer() (matching upstream).
# The mismatch only compiles when pdf_use_skia=false, so enabling Skia (our
# config) is what unearths it. Rewrite every stale call site.
#
# The first version of this used a bare `python3 - <<EOF` with a CWD-relative
# `os.walk('.')`; in the musl container it printed nothing at all (no rename line,
# no traceback) and the compiler still saw the legacy call at
# fpdfsdk/fpdf_progressive.cpp:50, so the rewrite now walks the explicit source
# root, reports exactly what it touched and hard-fails if the stale call survives.
PDFIUM_SRC_ROOT="${PDFIUM_SRC:-$(pwd)}"
if [ ! -f "${PDFIUM_SRC_ROOT}/fpdfsdk/fpdf_progressive.cpp" ]; then
    echo "ERROR: ${PDFIUM_SRC_ROOT}/fpdfsdk/fpdf_progressive.cpp not found - cannot rewrite the legacy UseSkiaRenderer() call" >&2
    exit 1
fi
python3 - "${PDFIUM_SRC_ROOT}" <<'PY_EOF'
import os
import sys

root = sys.argv[1]
expression = 'CFX_GEModule::Get()->UseSkiaRenderer()'
normalized = 'CFX_GEModule::IsSkiaRenderer()'
identifier = 'UseSkiaRenderer'

changed = []
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in ('third_party', '.git', 'out')]
    for fname in filenames:
        if not fname.endswith(('.cpp', '.h', '.cc')):
            continue
        p = os.path.join(dirpath, fname)
        try:
            with open(p, 'r', encoding='utf-8') as f:
                content = f.read()
        except Exception:
            continue
        if identifier not in content:
            continue
        # Rewrite the exact expression first (calling a static member through -> is
        # legal but the fork dropped the member), then any remaining bare use.
        rewritten = content.replace(expression, normalized).replace(identifier, 'IsSkiaRenderer')
        if rewritten != content:
            with open(p, 'w', encoding='utf-8') as f:
                f.write(rewritten)
            changed.append(os.path.relpath(p, root))
print(f"  Rewrote legacy UseSkiaRenderer() call in {len(changed)} file(s): {changed}")
PY_EOF
LEGACY_RENDERER_LEFT="$(grep -rl 'UseSkiaRenderer' "${PDFIUM_SRC_ROOT}/fpdfsdk" "${PDFIUM_SRC_ROOT}/core" "${PDFIUM_SRC_ROOT}/public" "${PDFIUM_SRC_ROOT}/samples" 2>/dev/null || true)"
if [ -n "${LEGACY_RENDERER_LEFT}" ]; then
    echo "ERROR: legacy UseSkiaRenderer() still present after the rewrite in:" >&2
    echo "${LEGACY_RENDERER_LEFT}" >&2
    exit 1
fi

# PartitionAlloc's internal_allocator_forward.h uses unqualified size_t with only
# <new>/<type_traits> included. That works with Chromium's bundled libc++ (the
# only configuration upstream builds) but breaks with GCC 14's libstdc++ on the
# linux-x64 rows: "error: unknown type name 'size_t'". Make the header
# self-contained rather than switching linux-x64 to libc++.
PARTITION_ALLOC_HEADER="base/allocator/partition_allocator/src/partition_alloc/internal_allocator_forward.h"
if [ -f "${PARTITION_ALLOC_HEADER}" ] && ! grep -q '#include <cstddef>' "${PARTITION_ALLOC_HEADER}"; then
    echo "  Adding <cstddef> include to partition_alloc internal_allocator_forward.h..."
    python3 - "${PARTITION_ALLOC_HEADER}" <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    src = f.read()
if '#include <cstddef>' not in src and '#include <new>' in src:
    with open(path, 'w') as f:
        f.write(src.replace('#include <new>', '#include <cstddef>\n#include <new>', 1))
    print("  Added <cstddef> to internal_allocator_forward.h.")
PY_EOF
fi

# GCC 14's libstdc++ stopped pulling <cstdint> in transitively, and the fork's
# constants/form_flags.h declares uint32_t constants without including anything
# at all, so the linux-x64 rows stop with
#   ../../constants/form_flags.h:13:11: error: unknown type name 'uint32_t'
# (configurations that use Chromium's bundled libc++ never see this). Make the
# header self-contained instead of switching linux-x64 to libc++.
FORM_FLAGS_HEADER="constants/form_flags.h"
if [ -f "${FORM_FLAGS_HEADER}" ] && ! grep -q '#include <cstdint>' "${FORM_FLAGS_HEADER}"; then
    echo "  Adding <cstdint> include to constants/form_flags.h..."
    python3 - "${FORM_FLAGS_HEADER}" <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    src = f.read()
guard = '#define CONSTANTS_FORM_FLAGS_H_\n'
if '#include <cstdint>' not in src and guard in src:
    with open(path, 'w') as f:
        f.write(src.replace(guard, guard + '\n#include <cstdint>\n', 1))
    print("  Added <cstdint> to form_flags.h.")
PY_EOF
fi

# PartitionAlloc's aarch64_support.h defines HAS_HW_CAPS on any Linux/arm64 and
# then includes <sys/ifunc.h> plus <asm/hwcap.h>, and its IsBtiEnabled/IsMteEnabled
# bodies use _IFUNC_ARG_HWCAP and struct __ifunc_arg_t from that glibc-only header.
# musl has neither, so the linux-musl-arm64 rows died with
#   ../../base/allocator/partition_alloc/src/partition_alloc/aarch64_support.h:19:10:
#   fatal error: 'sys/ifunc.h' file not found
# Merely guarding the include is not enough (the macro/struct uses would then fail),
# so gate HAS_HW_CAPS on the header being present; musl then takes the existing
# #else path (forward declare __ifunc_arg_t, both helpers return false).
AARCH64_SUPPORT_HEADER="base/allocator/partition_allocator/src/partition_alloc/aarch64_support.h"
if [ -f "${AARCH64_SUPPORT_HEADER}" ] && ! grep -q 'PA_PDFIUM_HAS_IFUNC_HEADER' "${AARCH64_SUPPORT_HEADER}"; then
    echo "  Gating partition_alloc HAS_HW_CAPS on <sys/ifunc.h> availability..."
    python3 - "${AARCH64_SUPPORT_HEADER}" <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    src = f.read()
target = """#if PA_BUILDFLAG(IS_ANDROID) || PA_BUILDFLAG(IS_LINUX)
#define HAS_HW_CAPS
#endif

#if PA_BUILDFLAG(PA_ARCH_CPU_ARM64) && defined(HAS_HW_CAPS)
#include <asm/hwcap.h>
#include <sys/ifunc.h>
#else
struct __ifunc_arg_t;
#endif"""
replacement = """#if defined(__has_include)
#if __has_include(<sys/ifunc.h>)
#define PA_PDFIUM_HAS_IFUNC_HEADER 1
#endif
#else
#define PA_PDFIUM_HAS_IFUNC_HEADER 1
#endif

#if (PA_BUILDFLAG(IS_ANDROID) || PA_BUILDFLAG(IS_LINUX)) && defined(PA_PDFIUM_HAS_IFUNC_HEADER)
#define HAS_HW_CAPS
#endif
#undef PA_PDFIUM_HAS_IFUNC_HEADER

#if PA_BUILDFLAG(PA_ARCH_CPU_ARM64) && defined(HAS_HW_CAPS)
#include <asm/hwcap.h>
#include <sys/ifunc.h>
#else
struct __ifunc_arg_t;
#endif"""
if target in src:
    with open(path, 'w') as f:
        f.write(src.replace(target, replacement, 1))
    print("  Gated HAS_HW_CAPS on <sys/ifunc.h> in aarch64_support.h.")
PY_EOF
fi

# PartitionAlloc's tagging.cc also includes <sys/ifunc.h> when HAS_MEMORY_TAGGING
# is enabled. musl libc does not have <sys/ifunc.h>, so guard the include.
TAGGING_CC="base/allocator/partition_allocator/src/partition_alloc/tagging.cc"
if [ -f "${TAGGING_CC}" ] && ! grep -q '__has_include(<sys/ifunc.h>)' "${TAGGING_CC}"; then
    echo "  Guarding <sys/ifunc.h> in partition_alloc tagging.cc..."
    python3 - "${TAGGING_CC}" <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    src = f.read()
target = """#if PA_BUILDFLAG(HAS_MEMORY_TAGGING)
#include <arm_acle.h>
#include <asm/hwcap.h>
#include <sys/auxv.h>
#include <sys/ifunc.h>"""
replacement = """#if PA_BUILDFLAG(HAS_MEMORY_TAGGING)
#include <arm_acle.h>
#include <asm/hwcap.h>
#include <sys/auxv.h>
#if defined(__has_include)
#if __has_include(<sys/ifunc.h>)
#include <sys/ifunc.h>
#else
struct __ifunc_arg_t;
#endif
#else
#include <sys/ifunc.h>
#endif"""
if target in src:
    with open(path, 'w') as f:
        f.write(src.replace(target, replacement, 1))
    print("  Guarded <sys/ifunc.h> in tagging.cc.")
PY_EOF
fi

# musl's dynamic loader cannot resolve PartitionAlloc's MTE ifuncs across shared
# objects, so the linux-musl-arm64 component smoke test dies with
#   dlopen failed: Error relocating libbase_allocator_..._allocator_core.so:
#   _ZN15partition_alloc8internal21RemaskPointerInternalEPv: symbol not found
# (RemaskPointerInternal/TagMemoryRange*Internal are __attribute__((ifunc)) under
# #if PA_BUILDFLAG(HAS_MEMORY_TAGGING); the same-page static row is fine because
# the symbol then lives in the same object). has_memory_tagging is computed in
# partition_alloc.gni for every arm64+clang Linux target, and the resolver would
# return the NoOp variants on musl anyway (our aarch64_support.h gate already
# forces IsBtiEnabled/IsMteEnabled to false), so turn the feature off for musl
# and keep the ifunc symbols out of the build entirely.
PA_GNI="base/allocator/partition_allocator/partition_alloc.gni"
if [ "${JPDFIUM_LIBC:-}" = "musl" ] && [ -f "${PA_GNI}" ] && \
    ! grep -q 'JPDFIUM musl: memory tagging off' "${PA_GNI}"; then
    echo "  Disabling PartitionAlloc memory tagging for musl..."
    python3 - "${PA_GNI}" <<'PY_EOF'
import sys
path = sys.argv[1]
with open(path) as f:
    src = f.read()
target = """has_memory_tagging = current_cpu == "arm64" && is_clang && !is_asan &&
                     !is_hwasan && (is_linux || is_android)"""
replacement = """# JPDFIUM musl: memory tagging off. The ifunc-based MTE helpers
# (RemaskPointerInternal and friends) cannot be resolved by musl's loader in a
# component build, and the resolvers would pick the NoOp variants on musl anyway.
has_memory_tagging = false  # JPDFIUM musl: memory tagging off"""
if target in src:
    with open(path, 'w') as f:
        f.write(src.replace(target, replacement, 1))
    print("  Disabled HAS_MEMORY_TAGGING in partition_alloc.gni.")
elif 'JPDFIUM musl: memory tagging off' in src:
    print("  HAS_MEMORY_TAGGING already disabled in partition_alloc.gni.")
else:
    raise SystemExit("ERROR: has_memory_tagging block not found in " + path)
PY_EOF
fi

# Chromium's build/ dep hard-pins the Windows SDK it was tested with
# (SDK_VERSION = '10.0.28000.0' in build/toolchain/win/setup_toolchain.py) and
# passes it to vcvarsall.bat. The GitHub windows runners do not have that SDK
# installed, so setup_toolchain.py aborts with an INCLUDE pointing at a missing
# SDK. The workflow exports the newest SDK that IS installed as
# JPDFIUM_WIN_SDK; repin the constant to it (an older-but-present SDK builds
# PDFium fine).
#
# Repinning the SDK version alone is not enough: the same build/ revision also
# hard-codes the NTDDI version it expects the SDK to implement, and the SDK
# headers switch declarations on it. At build_revision ebdaeefa (EmbedPDF
# ed2f716) that is "NTDDI_VERSION=NTDDI_WIN11_BR" (0x0A000012, Windows 11 26H1
# / SDK 10.0.28000). The newest SDK the runners have is 10.0.26100, whose
# sdkddkver.h stops at NTDDI_WIN11_GE (0x0A000010), so the undefined identifier
# evaluates to 0 and every "#if (NTDDI_VERSION >= NTDDI_VISTA)" guard in the
# SDK's own headers takes the wrong branch: minwinbase.h stops declaring
# FILE_INFO_BY_HANDLE_CLASS and fileapi.h(1081) / winbase.h(9388) fail with
# "error: unknown type name 'FILE_INFO_BY_HANDLE_CLASS'" while compiling
# PartitionAlloc's log_message.obj / logging.obj. Repin the define to the newest
# macro the installed SDK does declare (for 26100 that is NTDDI_WIN11_GE, the
# configuration the last green run used).
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        WIN_TOOLCHAIN_SETUP="build/toolchain/win/setup_toolchain.py"
        if [ -n "${JPDFIUM_WIN_SDK:-}" ] && [ -f "${WIN_TOOLCHAIN_SETUP}" ]; then
            echo "  Pinning Windows SDK_VERSION to ${JPDFIUM_WIN_SDK}..."
            sed_i "s/^SDK_VERSION = '.*'/SDK_VERSION = '${JPDFIUM_WIN_SDK}'/" "${WIN_TOOLCHAIN_SETUP}"

            WIN_BUILD_GN="build/config/win/BUILD.gn"
            WIN_SDK_DDKVER=""
            for sdk_root in "/c/Program Files (x86)/Windows Kits/10/include" "/c/Program Files/Windows Kits/10/include"; do
                for sdk_sub in um shared; do
                    if [ -f "${sdk_root}/${JPDFIUM_WIN_SDK}/${sdk_sub}/sdkddkver.h" ]; then
                        WIN_SDK_DDKVER="${sdk_root}/${JPDFIUM_WIN_SDK}/${sdk_sub}/sdkddkver.h"
                        break 2
                    fi
                done
            done
            if [ -f "${WIN_BUILD_GN}" ] && [ -n "${WIN_SDK_DDKVER}" ]; then
                CHROMIUM_NTDDI="$(grep -oE 'NTDDI_VERSION=NTDDI_[0-9A-Z_]+' "${WIN_BUILD_GN}" | head -n 1 | cut -d= -f2)"
                if [ -n "${CHROMIUM_NTDDI}" ]; then
                    SDK_NTDDI="$(python3 - "${WIN_SDK_DDKVER}" "${CHROMIUM_NTDDI}" <<'PY_EOF'
import sys, re
header, target = sys.argv[1], sys.argv[2]
with open(header, 'rb') as f:
    text = f.read().decode('utf-8', errors='ignore').replace('\r', '')
if re.search(r'^#define\s+' + re.escape(target) + r'\b', text, re.M):
    sys.exit(0)
defs = re.findall(r'^#define\s+(NTDDI_WIN[0-9A-Za-z_]*)\s+(0x[0-9A-Fa-f]+)', text, re.M)
if not defs:
    sys.exit(1)
defs.sort(key=lambda x: int(x[1], 16))
print(defs[-1][0])
PY_EOF
)"
                    if [ -n "${SDK_NTDDI}" ]; then
                        echo "  Repinning NTDDI_VERSION ${CHROMIUM_NTDDI} -> ${SDK_NTDDI} (not declared by SDK ${JPDFIUM_WIN_SDK})..."
                        sed_i "s/NTDDI_VERSION=${CHROMIUM_NTDDI}/NTDDI_VERSION=${SDK_NTDDI}/" "${WIN_BUILD_GN}"
                    fi
                fi
            fi
        elif [ -z "${JPDFIUM_WIN_SDK:-}" ]; then
            echo "  WARNING: JPDFIUM_WIN_SDK is not set; leaving the pinned SDK_VERSION in place." >&2
        fi
        ;;
esac

# GN runs some of its build steps through Chromium's DEPS-provided host CPython
# (third_party/cpython3/host/bin/python3). That binary is built against glibc, so
# on Alpine it cannot run at all:
#   ERROR at //build/toolchain/concurrent_links.gni:155:19: Script returned
#   non-zero exit code.
#     _command_dict = exec_script("get_concurrent_links.py", _args, "scope")
#   Error relocating .../third_party/cpython3/host/bin/python3:
#   posix_fallocate64: symbol not found        (preadv64v2/pwritev64v2 on arm64)
#   Returned 127.
# Replace it with a shim that execs the system python3 (same reasoning as the
# VPYTHON_BYPASS export above). Done after gclient sync so the download hook
# cannot overwrite it again.
if [ "${JPDFIUM_LIBC:-}" = "musl" ]; then
    HOST_PYTHON3="third_party/cpython3/host/bin/python3"
    if [ -f "${HOST_PYTHON3}" ] && [ "$(head -c 2 "${HOST_PYTHON3}")" != "#!" ]; then
        HOST_PYTHON3_BIN="$(command -v python3 || echo python3)"
        echo "  musl/Alpine: replacing the glibc host python3 with ${HOST_PYTHON3_BIN}..."
        mv "${HOST_PYTHON3}" "${HOST_PYTHON3}.glibc"
        printf '#!/bin/sh\nexec "%s" "$@"\n' "${HOST_PYTHON3_BIN}" > "${HOST_PYTHON3}"
        chmod +x "${HOST_PYTHON3}"
    fi

    # Chromium's DEPS downloads glibc binaries of Clang/LLD under
    # third_party/llvm-build/Release+Asserts/bin. On Alpine/musl, running those
    # glibc binaries directly fails (either crashing with unexpected "(" when
    # executed as a script on arm64 or dying in multithreaded libstdc++ synchronization
    # under gcompat with std::system_error on x64). Alpine ships native musl clang
    # and lld (installed by apk add clang lld in musl.sh). Replace the glibc LLVM
    # binaries with symlinks/wrappers to the system toolchain so compilation and
    # linking run natively on musl.
    # Diagnostic: Alpine's /bin/sh is busybox ash. Chromium hands the compiler
    # shell-escaped flags that can contain '*' (see the clang wrapper below). If
    # this shell expands that escaped star, the argument reaches clang already
    # globbed and no wrapper can repair it. Log the behaviour once so CI shows
    # which layer is responsible.
    PROBE_DIR="$(mktemp -d)"
    : > "${PROBE_DIR}/args.gn"
    : > "${PROBE_DIR}/build.ninja"
    PROBE_OUT="$(cd "${PROBE_DIR}" && /bin/sh -c 'printf "%s" -DSK=\(\(\(FREETYPE_MAJOR\)\ \*\ 0x01000000\)')"
    rm -rf "${PROBE_DIR}"
    echo "  musl/Alpine sh glob probe: [${PROBE_OUT}]"

    LLVM_BIN_DIR="third_party/llvm-build/Release+Asserts/bin"
    if [ -d "${LLVM_BIN_DIR}" ]; then
        echo "  musl/Alpine: redirecting third_party/llvm-build to Alpine system toolchain..."
        SYS_CLANG="$(command -v clang || true)"
        SYS_CLANGXX="$(command -v clang++ || true)"
        SYS_LLD="$(command -v ld.lld || command -v lld || true)"
        SYS_READELF="$(command -v llvm-readelf || command -v readelf || true)"
        SYS_NM="$(command -v llvm-nm || command -v nm || true)"
        SYS_AR="$(command -v llvm-ar || command -v ar || true)"

        if [ -n "${SYS_CLANG}" ]; then
            rm -f "${LLVM_BIN_DIR}/clang"
            cat > "${LLVM_BIN_DIR}/clang" <<WRAPPER_EOF
#!/bin/sh
# Chromium hands clang shell-escaped flags, so one argv can legitimately carry
# spaces, parentheses and '*' (for example
# -DSK_FREETYPE_MINIMUM_RUNTIME_VERSION=(((FREETYPE_MAJOR) * 0x01000000) | ...)).
# Re-parsing those through eval expanded that '*' as a glob against the build
# directory, which broke SkFontHost_FreeType.cpp with "token is not a valid
# binary operator in a preprocessor subexpression". Rotate the filtered
# arguments through the positional parameters instead: every byte of every
# argument survives untouched.
n=\$#
i=0
has_c_file=0
has_x=0
is_compile_only=0
while [ "\$i" -lt "\$n" ]; do
    arg=\$1
    shift
    case "\$arg" in
        -fdiagnostics-show-inlining-chain|-fno-lifetime-dse|-fsanitize-ignore-for-ubsan-feature=*|-Wa,--crel*|-Wa,--allow-experimental-crel*)
            i=\$((i + 1))
            continue
            ;;
        *.c) has_c_file=1 ;;
        -c) is_compile_only=1 ;;
        -x) has_x=1 ;;
    esac
    set -- "\$@" "\$arg"
    i=\$((i + 1))
done
if [ "\$has_c_file" = "1" ] && [ "\$is_compile_only" = "1" ] && [ "\$has_x" = "0" ]; then
    exec "${SYS_CLANG}" --driver-mode=gcc -x c "\$@"
fi
exec "${SYS_CLANG}" --driver-mode=gcc "\$@"
WRAPPER_EOF
            chmod +x "${LLVM_BIN_DIR}/clang"
        fi
        if [ -n "${SYS_CLANGXX}" ]; then
            rm -f "${LLVM_BIN_DIR}/clang++"
            cat > "${LLVM_BIN_DIR}/clang++" <<WRAPPER_EOF
#!/bin/sh
# Same rotation scheme as the clang wrapper: never re-parse argv through eval,
# because Chromium's escaped flag values legitimately contain '*' and spaces.
n=\$#
i=0
has_c_file=0
has_x=0
is_compile_only=0
while [ "\$i" -lt "\$n" ]; do
    arg=\$1
    shift
    case "\$arg" in
        -fdiagnostics-show-inlining-chain|-fno-lifetime-dse|-fsanitize-ignore-for-ubsan-feature=*|-Wa,--crel*|-Wa,--allow-experimental-crel*)
            i=\$((i + 1))
            continue
            ;;
        *.c) has_c_file=1 ;;
        -c) is_compile_only=1 ;;
        -x) has_x=1 ;;
    esac
    set -- "\$@" "\$arg"
    i=\$((i + 1))
done
if [ "\$has_c_file" = "1" ] && [ "\$is_compile_only" = "1" ] && [ "\$has_x" = "0" ]; then
    exec "${SYS_CLANGXX}" --driver-mode=g++ -D_LIBCPP_HAS_MUSL_LIBC -D_LIBCPP_PROVIDES_DEFAULT_RUNE_TABLE -x c "\$@"
fi
exec "${SYS_CLANGXX}" --driver-mode=g++ -D_LIBCPP_HAS_MUSL_LIBC -D_LIBCPP_PROVIDES_DEFAULT_RUNE_TABLE "\$@"
WRAPPER_EOF
            chmod +x "${LLVM_BIN_DIR}/clang++"
        fi
        if [ -n "${SYS_LLD}" ]; then
            ln -sf "${SYS_LLD}" "${LLVM_BIN_DIR}/ld.lld"
            ln -sf "${SYS_LLD}" "${LLVM_BIN_DIR}/lld"
            ln -sf "${SYS_LLD}" "${LLVM_BIN_DIR}/lld-link"
        fi
        if [ -n "${SYS_READELF}" ]; then
            ln -sf "${SYS_READELF}" "${LLVM_BIN_DIR}/llvm-readelf"
        fi
        if [ -n "${SYS_NM}" ]; then
            ln -sf "${SYS_NM}" "${LLVM_BIN_DIR}/llvm-nm"
        fi
        if [ -n "${SYS_AR}" ]; then
            ln -sf "${SYS_AR}" "${LLVM_BIN_DIR}/llvm-ar"
        fi
    fi
fi

# ---------- Step 5: GN configuration ----------
echo "[5/7] Configuring GN build..."
OUT_DIR="out/Release"

# Two build flavors selected by ${JPDFIUM_BUILD_MODE:-component}:
#
#   "component" (default, used by snapshot.yml / publish-github-packages.yml /
#       ci.yml - iteration-friendly path):
#         is_component_build=true → SOLINK each PDFium subcomponent into its
#         own .so. Output: libpdfium.so + ~15 sibling .so files (abseil,
#         partition_alloc, libchrome_zlib, icuuc, libpng, freetype, …) that
#         the bridge links against at the consumer end. Build is fast and
#         per-component, debugging is easier.
#
#   "v8" / "xfa" (experimental, linux-x64 only): add PDF JavaScript
#       (pdf_enable_v8=true) and, for xfa, the incomplete XFA forms engine
#       (pdf_enable_xfa=true, implies V8). Large and slow to build; not
#       consumed by any default workflow.
#
# All modes enable the Skia renderer alongside AGG (pdf_use_skia=true
# pdf_use_agg=true). Skia is the default at runtime (jpdfium_init() picks it
# when present); jpdfium_init_ex(0) forces AGG.
#
#   "static" (release-only, used by release.yml - release-size-optimized
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
#   visibility("default") - in static mode we instead pass these via
#   compile flags below.
JPDFIUM_BUILD_MODE="${JPDFIUM_BUILD_MODE:-component}"
case "$JPDFIUM_BUILD_MODE" in
    component)
        GN_ARGS='is_debug=false is_component_build=true pdf_is_standalone=true pdf_enable_v8=false pdf_enable_xfa=false pdf_use_skia=true pdf_use_agg=true pdf_use_partition_alloc=true use_remoteexec=false clang_use_chrome_plugins=false treat_warnings_as_errors=false symbol_level=0 use_sysroot=false use_custom_libcxx=false use_allocator_shim=false'
        ;;
    v8)
        GN_ARGS='is_debug=false is_component_build=true pdf_is_standalone=true pdf_enable_v8=true pdf_enable_xfa=false pdf_use_skia=true pdf_use_agg=true pdf_use_partition_alloc=true use_remoteexec=false clang_use_chrome_plugins=false treat_warnings_as_errors=false symbol_level=0 use_sysroot=false use_custom_libcxx=false use_allocator_shim=false v8_enable_sandbox=false'
        ;;
    xfa)
        GN_ARGS='is_debug=false is_component_build=true pdf_is_standalone=true pdf_enable_v8=true pdf_enable_xfa=true pdf_use_skia=true pdf_use_agg=true pdf_use_partition_alloc=true use_remoteexec=false clang_use_chrome_plugins=false treat_warnings_as_errors=false symbol_level=0 use_sysroot=false use_custom_libcxx=false use_allocator_shim=false v8_enable_sandbox=false'
        ;;
    static)
        GN_ARGS='is_debug=false is_component_build=false pdf_is_complete_lib=true pdf_is_standalone=true pdf_enable_v8=false pdf_enable_xfa=false pdf_use_skia=true pdf_use_agg=true pdf_use_partition_alloc=true use_remoteexec=false clang_use_chrome_plugins=false treat_warnings_as_errors=false symbol_level=0 use_sysroot=false use_custom_libcxx=false use_allocator_shim=false'
        ;;
    *)
        echo "ERROR: unknown JPDFIUM_BUILD_MODE=$JPDFIUM_BUILD_MODE (expected: component, static, v8, xfa)" >&2
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
# explicitly invoke install-sysroot.py - gclient sync's hooks don't pull
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

# musl / Alpine target. Set JPDFIUM_LIBC=musl (the linux-musl-* prebuild rows
# run inside an Alpine container so the system toolchain + libc are musl).
# PDFium/Chromium has no official musl support, so this is the CI-proving part:
#   - Chromium's bundled clang is a glibc binary; in Alpine it needs `apk add
#     gcompat` to run, or the build must point at Alpine's system clang.
#   - use_custom_libcxx=true: Alpine ships libstdc++, not libc++, so let Chromium
#     build/export its own libc++ (same reasoning as the Windows branch below).
#   - use_sysroot=false: link against the container's musl system libraries.
# Expect EmbedPDF fork patches may be needed; iterate via the Prebuild PDFium CI.
if [ "${JPDFIUM_LIBC:-}" = "musl" ]; then
    GN_ARGS="${GN_ARGS/use_custom_libcxx=false/use_custom_libcxx=true}"
    GN_ARGS="${GN_ARGS} use_clang_modules=false"
    echo "  musl/Alpine target: use_custom_libcxx=true, use_clang_modules=false, use_sysroot=false"
fi

# Windows has no system libc++, so use_custom_libcxx=false produces broken
# component builds - abseil-cpp.dll fails to link with undefined symbols on
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

# ---------- Step 5b: object-tree memoization ----------
# GetObjectsWithMultipleReferences() otherwise walks the object tree once per page.
echo "[5b/7] Applying multi-ref object cache patch..."
git -C "${PDFIUM_SRC}" checkout -- core/fpdfapi/parser/cpdf_document.h \
    core/fpdfapi/parser/object_tree_traversal_util.cpp 2>/dev/null || true
python3 "${SCRIPT_DIR}/patch-pdfium-objtree-cache.py" "${PDFIUM_SRC}" || exit 1

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
        # shellcheck disable=SC2034 # kept for parity with LIB_EXT (release wiring)
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
    SRC=$(find "${OUT_DIR}" -maxdepth 3 -type f \( -name "${MAIN_LIB}" -o -name "libembedpdf.${STATIC_EXT}" -o -name "embedpdf.${STATIC_EXT}" \) 2>/dev/null | head -1)
    if [ -z "$SRC" ] || [ ! -f "$SRC" ]; then
        echo "  ERROR: ${MAIN_LIB} not produced - check pdf_is_complete_lib args" >&2
        exit 1
    fi
    cp "$SRC" "${TARGET_DIR}/lib/${MAIN_LIB}"
    LIB_COUNT=1
    echo "  Copied static archive: ${SRC} ($(du -h "$SRC" | cut -f1)) -> ${TARGET_DIR}/lib/${MAIN_LIB}"
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
    if [ "${LIB_EXT}" = "dll" ] && [ -f "${TARGET_DIR}/lib/embedpdf.dll" ]; then
        cp "${TARGET_DIR}/lib/embedpdf.dll" "${MAIN_LIB_PATH}"
        cp "${TARGET_DIR}/lib/embedpdf.dll.lib" "${TARGET_DIR}/lib/pdfium.dll.lib" 2>/dev/null || true
    elif [ -f "${TARGET_DIR}/lib/libembedpdf.${LIB_EXT}" ]; then
        cp "${TARGET_DIR}/lib/libembedpdf.${LIB_EXT}" "${MAIN_LIB_PATH}"
    fi
fi
if [ ! -f "${MAIN_LIB_PATH}" ]; then
    echo "  ERROR: Main library not found at ${MAIN_LIB_PATH}" >&2
    exit 1
fi
echo "  Main library: ${MAIN_LIB_PATH}"

# Marker for consumers: every mode builds with the Skia renderer, which is
# the default at runtime (jpdfium_init()). The header's FPDF_RenderPageSkia
# declaration is guarded by PDF_USE_SKIA, which only the PDFium build defines,
# so an outside probe cannot see it otherwise.
: > "${TARGET_DIR}/lib/pdfium-use-skia"
echo "  Skia marker: ${TARGET_DIR}/lib/pdfium-use-skia"

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
