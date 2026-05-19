#!/usr/bin/env bash
# Bundle the JPDFium bridge's third-party shared-library dependencies next to
# libjpdfium.so/.dylib so the published natives jar is hermetic — downstream
# users don't need apt/brew/system-package installs at runtime.
#
# Linux: walk `ldd` recursively, skip libc/libm/etc., copy everything else.
#        libjpdfium.so is built with RUNPATH=$ORIGIN (set in CMakeLists.txt),
#        so the dynamic linker finds the bundled deps next to the bridge.
#
# macOS: walk `otool -L` recursively, skip /System and /usr/lib (always
#        present, signed), copy everything else. Rewrite each dependency
#        path with `install_name_tool -change` to use @loader_path so the
#        dyld finds bundled deps next to the bridge. The bridge itself is
#        built with INSTALL_RPATH=@loader_path (set in CMakeLists.txt).
#
# Windows: not needed — vcpkg DLLs are already copied wholesale by the
#          workflow's `Stage binaries` step, and Windows has no equivalent
#          of RUNPATH that we have to set on the bridge.
#
# Usage: bundle-runtime-deps.sh <platform>     e.g. linux-x64, darwin-arm64
set -euo pipefail

PLATFORM="${1:?platform required}"
DIST_DIR="native/dist/$PLATFORM"

if [ ! -d "$DIST_DIR" ]; then
    echo "ERROR: $DIST_DIR not found — staging step didn't run?" >&2
    exit 1
fi

bundle_linux() {
    local bridge="$DIST_DIR/libjpdfium.so"
    [ -f "$bridge" ] || { echo "no libjpdfium.so to bundle for"; return 0; }

    # Always-present system libs we don't need to (and shouldn't) bundle.
    # Bundling libc/libpthread/etc. can crash because the dynamic linker
    # already has its own copy loaded into the process.
    local skip_regex='^(linux-vdso|libc|libm|libdl|libpthread|libgcc_s|libresolv|librt|libstdc\+\+|ld-linux)\.so'

    # Recursive walk: queue of files to process; each file's ldd output gets
    # filtered and uncopied entries get copied + queued. We use file-existence
    # in DIST_DIR as the "seen" check so this works under bash 3.2 (macOS) too.
    local queue=("$bridge")
    while [ "${#queue[@]}" -gt 0 ]; do
        local target="${queue[0]}"
        queue=("${queue[@]:1}")

        while IFS= read -r line; do
            local name path
            name=$(awk '{print $1}' <<<"$line")
            # ldd lines come in two shapes:
            #   libfoo.so.0 => /abs/path/libfoo.so.0 (0x...)
            #   linux-vdso.so.1 (0x...)           <- no '=>'
            if ! grep -q '=>' <<<"$line"; then continue; fi
            path=$(awk '{print $3}' <<<"$line")
            [ -z "$path" ] && continue
            [ "$path" = "not" ] && continue  # "not found" stub
            [ ! -e "$path" ] && continue

            if echo "$name" | grep -qE "$skip_regex"; then continue; fi

            local base
            base=$(basename "$path")
            local dest="$DIST_DIR/$base"
            if [ ! -e "$dest" ]; then
                cp -v "$path" "$dest"
                queue+=("$dest")
            fi
        done < <(ldd "$target" 2>/dev/null || true)
    done

    # NOTE: deliberately NOT creating libfoo.so unversioned aliases for the
    # bundled SONAME-versioned files. The bridge's NEEDED entries reference
    # SONAMEs like libicudata.so.74 — that's what the runtime loader looks
    # up. Unversioned libfoo.so is a compile-time linker convention that
    # nothing needs at runtime. Skipping the alias copies saves the jar
    # 30+ MB on Linux (icudata, gnutls, unistring, p11-kit, libqpdf, etc.).

    # Linux's dynamic loader does NOT propagate DT_RUNPATH transitively (this
    # is a deliberate security restriction). The bridge already has
    # RUNPATH=$ORIGIN (set in CMakeLists.txt), but each bundled .so needs its
    # own RUNPATH=$ORIGIN so when libqpdf loads its dep libcrypto, the loader
    # looks in $ORIGIN (the dist dir) and finds the bundled libcrypto. Without
    # this, transitive deps fall back to system search and may not be found.
    if command -v patchelf >/dev/null 2>&1; then
        for f in "$DIST_DIR"/lib*.so*; do
            [ -e "$f" ] || continue
            [ -L "$f" ] && continue  # symlinks don't carry RUNPATH; their target does
            patchelf --set-rpath '$ORIGIN' "$f" 2>/dev/null || true
        done
    else
        echo "WARNING: patchelf not installed — transitive deps may not resolve at runtime" >&2
    fi
}

bundle_macos() {
    local bridge="$DIST_DIR/libjpdfium.dylib"
    [ -f "$bridge" ] || { echo "no libjpdfium.dylib to bundle for"; return 0; }

    # Use file existence in DIST_DIR as the "seen" marker — works under macOS'
    # bash 3.2 (which lacks declare -A) without needing brewed bash on PATH.
    local queue=("$bridge")
    while [ "${#queue[@]}" -gt 0 ]; do
        local target="${queue[0]}"
        queue=("${queue[@]:1}")

        # otool -L output: first line is the file itself, then deps. Pipe
        # protected with || true so bridges with zero non-system deps (or any
        # otool exit oddity) don't trip set -o pipefail.
        local deps
        deps=$(otool -L "$target" 2>/dev/null | tail -n +2 | awk '{print $1}' || true)
        [ -z "$deps" ] && continue

        while IFS= read -r dep; do
            [ -z "$dep" ] && continue
            case "$dep" in
                /System/*|/usr/lib/*) continue;;  # always present, signed
                @*) continue;;                    # already relative
            esac
            [ -f "$dep" ] || continue

            local base
            base=$(basename "$dep")
            local dest="$DIST_DIR/$base"
            local is_new=0
            if [ ! -e "$dest" ]; then
                cp -v "$dep" "$dest"
                is_new=1
            fi
            # Always ensure writability for install_name_tool (brew dylibs
            # come copied as 0644 owned by the runner, but better safe).
            chmod u+w "$dest" 2>/dev/null || true
            if [ "$is_new" = "1" ]; then
                # Set the lib's own id to @loader_path so anything linking
                # against it carries the relative reference.
                install_name_tool -id "@loader_path/$base" "$dest" 2>/dev/null || true
                queue+=("$dest")
            fi
            # Rewrite the consumer (target)'s dep reference to the bundled copy.
            install_name_tool -change "$dep" "@loader_path/$base" "$target" 2>/dev/null || true
        done <<<"$deps"
    done

    # Deliberately NOT codesign-adhoc here. Adhoc signatures in dylibs
    # nested inside the Spring Boot bootJar fail Tauri's codesign walk of
    # the .app bundle:
    #   "The signature of the binary is invalid."
    #   "The signature does not include a secure timestamp."
    # Tauri's macOS bundler re-signs every binary in the .app at packaging
    # time using the build's Developer ID identity; ad-hoc here just
    # produces a sig that fails verification before Tauri can overwrite it.
    # Bridge and bundled dylibs ship unsigned; downstream bundlers sign.
}

bundle_windows() {
    local bridge="$DIST_DIR/jpdfium.dll"
    [ -f "$bridge" ] || { echo "no jpdfium.dll to bundle for"; return 0; }

    # Walk import table of every DLL in DIST_DIR, copying the bridge's runtime
    # deps from vcpkg's installed bin while skipping Windows system DLLs.
    # Use dumpbin (ships with Visual Studio on github-runner windows-latest).
    if ! command -v dumpbin >/dev/null 2>&1; then
        # Fallback to PATH lookup — VS install dir varies. Try a few.
        local vs_dumpbin
        vs_dumpbin=$(find "/c/Program Files/Microsoft Visual Studio" \
                     -name 'dumpbin.exe' 2>/dev/null | head -1 || true)
        if [ -z "$vs_dumpbin" ]; then
            echo "ERROR: dumpbin not on PATH and not findable under VS install" >&2
            return 1
        fi
        DUMPBIN="$vs_dumpbin"
    else
        DUMPBIN=dumpbin
    fi

    # Windows DLLs we never need to ship — they're always present on a
    # Windows installation, signed and version-managed by the OS.
    is_system_dll() {
        local lc
        lc=$(echo "$1" | tr 'A-Z' 'a-z')
        case "$lc" in
            kernel*.dll|user32.dll|advapi32.dll|gdi32.dll|ole32.dll \
            |oleaut32.dll|ws2_32.dll|crypt32.dll|shell32.dll|shlwapi.dll \
            |comctl32.dll|comdlg32.dll|winmm.dll|ntdll.dll|userenv.dll \
            |bcrypt.dll|bcryptprimitives.dll|ncrypt.dll|secur32.dll \
            |version.dll|setupapi.dll|iphlpapi.dll|netapi32.dll|psapi.dll \
            |imm32.dll|dwmapi.dll|uxtheme.dll|rpcrt4.dll|wldap32.dll \
            |winhttp.dll|wininet.dll|opengl32.dll|gdiplus.dll|d3d*.dll \
            |dxgi.dll|mscoree.dll|mfplat.dll|propsys.dll|powrprof.dll \
            |dbgcore.dll|dbghelp.dll|msdia140.dll|symsrv.dll \
            |api-*.dll|ext-*.dll)
                return 0;;
        esac
        return 1
    }

    # Search locations for resolving a DLL by name.
    local search_dirs=(
        "$DIST_DIR"
        "$VCPKG_INSTALLATION_ROOT/installed/x64-windows/bin"
        "native/pdfium/lib"
    )
    find_dll() {
        local name="$1"
        local d
        for d in "${search_dirs[@]}"; do
            if [ -f "$d/$name" ]; then echo "$d/$name"; return 0; fi
        done
        return 1
    }

    # Recursive walk by basename — copy only first occurrence per name.
    local queue=("$bridge")
    while [ "${#queue[@]}" -gt 0 ]; do
        local target="${queue[0]}"
        queue=("${queue[@]:1}")
        # dumpbin /dependents lines are indented DLL names. Filter via regex.
        local deps
        deps=$("$DUMPBIN" //dependents "$target" 2>/dev/null \
               | grep -oE '[A-Za-z0-9_+.-]+\.[Dd][Ll][Ll]' \
               | sort -u || true)
        while IFS= read -r dep; do
            [ -z "$dep" ] && continue
            if is_system_dll "$dep"; then continue; fi
            # If we already have it in the dist dir we're done with this name.
            local dest="$DIST_DIR/$dep"
            if [ -e "$dest" ]; then continue; fi
            local src
            src=$(find_dll "$dep" || true)
            if [ -z "$src" ]; then
                echo "  (skipping $dep — not found in any source dir)" >&2
                continue
            fi
            cp -v "$src" "$dest"
            queue+=("$dest")
        done <<<"$deps"
    done
}

case "$PLATFORM" in
    linux-*)
        bundle_linux
        # Strip debug symbols from the bridge to slash binary size. The
        # build is is_debug=false / symbol_level=0 / -DCMAKE_BUILD_TYPE=Release
        # but Rust's #[no_mangle] + statically linked third-party crates still
        # leave megabytes of section info. Safe on shared libs.
        if command -v strip >/dev/null 2>&1; then
            strip --strip-unneeded "$DIST_DIR/libjpdfium.so" 2>/dev/null || true
            # Also strip the bundled libs — they came from /usr/lib already
            # stripped on most distros, but be defensive.
            for f in "$DIST_DIR"/lib*.so "$DIST_DIR"/lib*.so.*; do
                [ -L "$f" ] && continue
                [ -e "$f" ] || continue
                strip --strip-unneeded "$f" 2>/dev/null || true
            done
        fi
        ;;
    darwin-*)
        bundle_macos
        # macOS strip wants -S (debug symbols only) to keep the symbol table
        # the loader needs. -x would strip non-global symbols which can
        # break dlsym lookups.
        if command -v strip >/dev/null 2>&1; then
            strip -S "$DIST_DIR/libjpdfium.dylib" 2>/dev/null || true
            for f in "$DIST_DIR"/*.dylib; do
                [ -L "$f" ] && continue
                [ -e "$f" ] || continue
                strip -S "$f" 2>/dev/null || true
            done
        fi
        ;;
    windows-*)
        bundle_windows
        # The MSVC linker strips PE files in Release config already; no
        # equivalent `strip` step needed.
        ;;
    *)
        echo "Unknown platform: $PLATFORM" >&2
        exit 1
        ;;
esac

echo ""
echo "Final bundle contents for $PLATFORM:"
ls -la "$DIST_DIR/"
