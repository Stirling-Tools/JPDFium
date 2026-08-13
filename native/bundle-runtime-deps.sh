#!/usr/bin/env bash
# Bundle the JPDFium bridge's third-party shared-library dependencies next to
# libjpdfium.so/.dylib so the published natives jar is hermetic - downstream
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
# Windows: walk the bridge's import table with dumpbin and copy the runtime
#          DLLs it references from vcpkg's installed/<triplet>/bin (skipping
#          Windows system DLLs). Triplet is x64-windows or arm64-windows.
#
# Usage: bundle-runtime-deps.sh <platform>     e.g. linux-x64, darwin-arm64
set -euo pipefail

PLATFORM="${1:?platform required}"
DIST_DIR="native/dist/$PLATFORM"

if [ ! -d "$DIST_DIR" ]; then
    echo "ERROR: $DIST_DIR not found - staging step didn't run?" >&2
    exit 1
fi

bundle_linux() {
    local bridge="${BUNDLE_ROOT:-$DIST_DIR/libjpdfium.so}"
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
    # SONAMEs like libicudata.so.74 - that's what the runtime loader looks
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
            # No bundled lib may demand an executable stack - LXC/hardened
            # kernels refuse to load those (#6869).
            if readelf -lW "$f" 2>/dev/null | grep "GNU_STACK" | grep -q "RWE"; then
                patchelf --clear-execstack "$f" 2>/dev/null \
                    || echo "WARNING: $f demands executable stack and patchelf could not clear it" >&2
            fi
        done
    else
        echo "WARNING: patchelf not installed - transitive deps may not resolve at runtime" >&2
    fi
}

bundle_macos() {
    local bridge="${BUNDLE_ROOT:-$DIST_DIR/libjpdfium.dylib}"
    [ -f "$bridge" ] || { echo "no libjpdfium.dylib to bundle for"; return 0; }

    # Known macOS install prefixes for searching @rpath/@loader_path deps.
    # Brew on Apple Silicon installs to /opt/homebrew; brew on Intel to
    # /usr/local. Our qpdf-native and harfbuzz-no-glib builds install to
    # the brew prefix on both archs. /usr/local stays as a fallback for
    # Intel hosts.
    #
    # Keg-only formulas (icu4c@78, openssl@3, libgcrypt, gnutls, etc.) live
    # at <prefix>/opt/<formula>/lib/ - not symlinked into <prefix>/lib/. We
    # add a glob fallback below so any keg-only formula's libs are found
    # without having to enumerate them by name.
    local rpath_dirs=(/opt/homebrew/lib /usr/local/lib /usr/local/opt/icu4c/lib)
    local rpath_globs=(/opt/homebrew/opt/*/lib /usr/local/opt/*/lib)

    # Resolve a dep that came back as @rpath/foo.dylib or @loader_path/foo.dylib
    # by basename-searching through rpath_dirs (fixed list) then rpath_globs
    # (keg-only formulas). echoes the absolute path or empty if nothing matches.
    _resolve_rpath_dep() {
        local d="$1"
        local base
        base=$(basename "$d")
        local dir
        for dir in "${rpath_dirs[@]}"; do
            if [ -f "$dir/$base" ]; then
                echo "$dir/$base"
                return 0
            fi
        done
        # Glob expansion runs at function-call time, picking up whatever
        # keg-only formulas brew has installed. Quote the basename test to
        # tolerate dirs that don't exist (the glob can return its literal).
        for dir in "${rpath_globs[@]}"; do
            for d2 in $dir; do
                [ -d "$d2" ] || continue
                if [ -f "$d2/$base" ]; then
                    echo "$d2/$base"
                    return 0
                fi
            done
        done
        return 1
    }

    # Use file existence in DIST_DIR as the "seen" marker - works under macOS'
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
            esac

            # The raw dep string we'll rewrite later - preserve so
            # install_name_tool -change matches what's actually in the
            # binary's load commands.
            local orig_dep="$dep"

            case "$dep" in
                @rpath/*|@loader_path/*|@executable_path/*)
                    # Resolve through known install prefixes. If we can't,
                    # the dep is something we didn't build (or it lives in
                    # an unexpected location) - skip and let dyld fail at
                    # runtime instead of silently shipping a half-bundle.
                    local resolved
                    if ! resolved=$(_resolve_rpath_dep "$dep"); then
                        echo "  (bundle_macos: can't resolve $dep - skipping)" >&2
                        continue
                    fi
                    dep="$resolved"
                    ;;
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
            # Rewrite the consumer (target)'s dep reference to the bundled
            # copy. Use $orig_dep (what's literally in the load command)
            # not $dep (the resolved absolute path) - install_name_tool
            # -change has to match exactly.
            install_name_tool -change "$orig_dep" "@loader_path/$base" "$target" 2>/dev/null || true
        done <<<"$deps"
    done
}

sign_macos() {
    # On Apple Silicon the kernel refuses to load UNSIGNED arm64 code. When a
    # Developer ID identity is available (CI sets MACOS_SIGN_IDENTITY after
    # importing the cert via native/import-macos-cert.sh) we sign the bridge
    # and every bundled dylib with a real, securely-timestamped Developer ID
    # signature.
    #
    # A genuine Developer ID signature WITH a secure timestamp also satisfies
    # the downstream Tauri bundler's pre-sign verification walk of the .app.
    # The earlier ad-hoc signature failed that walk:
    #   "The signature of the binary is invalid."
    #   "The signature does not include a secure timestamp."
    # Tauri can still force-re-sign each binary in the .app with its own
    # Developer ID over the top of ours, so this stays compatible with it.
    #
    # Signing is MANDATORY: unsigned macOS dylibs are not a supported output.
    # If no identity is available we fail the build rather than silently
    # shipping libs that won't load on Apple Silicon. Set up the keychain via
    # native/import-macos-cert.sh (CI) before running this.
    local identity="${MACOS_SIGN_IDENTITY:-}"
    if [ -z "$identity" ]; then
        # PR builds have no signing cert and never publish - allow unsigned
        # there so the bridge build/bundle still gets CI coverage.
        if [ "${MACOS_ALLOW_UNSIGNED:-}" = "1" ]; then
            echo "WARNING: MACOS_SIGN_IDENTITY not set - skipping code-signing (MACOS_ALLOW_UNSIGNED=1). These dylibs must not ship." >&2
            return 0
        fi
        echo "ERROR: MACOS_SIGN_IDENTITY not set - refusing to ship unsigned" \
             "macOS dylibs. Import a Developer ID cert first" \
             "(native/import-macos-cert.sh)." >&2
        exit 1
    fi

    local keychain_args=()
    if [ -n "${MACOS_KEYCHAIN_PATH:-}" ]; then
        keychain_args=(--keychain "$MACOS_KEYCHAIN_PATH")
    fi

    echo "Code-signing dylibs with identity: $identity"
    local f
    for f in "$DIST_DIR"/*.dylib; do
        [ -L "$f" ] && continue   # skip version symlinks
        [ -e "$f" ] || continue
        # --options runtime keeps these notarization-ready; harmless for a
        # dylib loaded into the JVM (library validation is governed by the
        # host java executable, not by us).
        codesign --force --timestamp --options runtime \
                 --sign "$identity" "${keychain_args[@]}" "$f"
        codesign --verify --strict --verbose=2 "$f"
    done
    echo "Signed $(ls -1 "$DIST_DIR"/*.dylib 2>/dev/null | wc -l | tr -d ' ') dylib(s)."
}

bundle_windows() {
    local bridge="${BUNDLE_ROOT:-$DIST_DIR/jpdfium.dll}"
    [ -f "$bridge" ] || { echo "no jpdfium.dll to bundle for"; return 0; }

    # Walk import table of every DLL in DIST_DIR, copying the bridge's runtime
    # deps from vcpkg's installed bin while skipping Windows system DLLs.
    # Use dumpbin (ships with Visual Studio on github-runner windows-latest).
    if ! command -v dumpbin >/dev/null 2>&1; then
        # Fallback to PATH lookup - VS install dir varies. Try a few.
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

    # Windows DLLs we never need to ship - they're always present on a
    # Windows installation, signed and version-managed by the OS.
    #
    # We intentionally DO NOT skip msvcp140 / vcruntime140 / ucrtbase here:
    # the bridge is built with MSVC default /MD (dynamic CRT), so these are
    # real link-time deps. A pure-Java user who installs Java but not the VS
    # Redistributable would otherwise see "msvcp140.dll not found" at JNI
    # load. The proper fix to drop them is paired with /MT + vcpkg
    # x64-windows-static - tracked as a follow-up.
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

    # Search locations for resolving a DLL by name. vcpkg's installed-tree
    # folder is triplet-scoped (x64-windows vs arm64-windows on ARM64), so
    # derive it from the platform instead of hardcoding x64.
    local vcpkg_triplet="x64-windows"
    case "$PLATFORM" in
        windows-arm64|vips-windows-arm64) vcpkg_triplet="arm64-windows" ;;
    esac
    local search_dirs=(
        "$DIST_DIR"
        "$VCPKG_INSTALLATION_ROOT/installed/$vcpkg_triplet/bin"
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

    # Recursive walk by basename - copy only first occurrence per name.
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
                echo "  (skipping $dep - not found in any source dir)" >&2
                continue
            fi
            cp -v "$src" "$dest"
            queue+=("$dest")
        done <<<"$deps"
    done
}

case "$PLATFORM" in
    linux-*|vips-linux-*)
        bundle_linux
        # Strip debug symbols from the bridge to slash binary size. The
        # build is is_debug=false / symbol_level=0 / -DCMAKE_BUILD_TYPE=Release
        # but Rust's #[no_mangle] + statically linked third-party crates still
        # leave megabytes of section info. Safe on shared libs.
        if command -v strip >/dev/null 2>&1; then
            strip --strip-unneeded "$DIST_DIR/libjpdfium.so" 2>/dev/null || true
            # Also strip the bundled libs - they came from /usr/lib already
            # stripped on most distros, but be defensive.
            for f in "$DIST_DIR"/lib*.so "$DIST_DIR"/lib*.so.*; do
                [ -L "$f" ] && continue
                [ -e "$f" ] || continue
                strip --strip-unneeded "$f" 2>/dev/null || true
            done
        fi
        ;;
    darwin-*|vips-darwin-*)
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
        sign_macos
        ;;
    windows-*|vips-windows-*)
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
