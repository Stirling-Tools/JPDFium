#!/usr/bin/env bash
# Bundle native shared-library runtime dependencies into native/dist/<platform>/.
set -euo pipefail

PLATFORM="${1:?platform required}"
DIST_DIR="native/dist/$PLATFORM"

if [ ! -d "$DIST_DIR" ]; then
    echo "ERROR: $DIST_DIR not found" >&2
    exit 1
fi

bundle_linux() {
    export LD_LIBRARY_PATH="/usr/local/lib:${LD_LIBRARY_PATH:-}"
    local bridge="${BUNDLE_ROOT:-$DIST_DIR/libjpdfium.so}"
    [ -f "$bridge" ] || { echo "no libjpdfium.so found to bundle"; return 0; }

    local skip_regex='^(linux-vdso|libc|libc\.musl|libm|libdl|libpthread|libgcc_s|libresolv|librt|libstdc\+\+)\.so|^ld-linux|^ld-musl'

    local queue=("$bridge")
    while [ "${#queue[@]}" -gt 0 ]; do
        local target="${queue[0]}"
        queue=("${queue[@]:1}")

        while IFS= read -r line; do
            local name path
            name=$(awk '{print $1}' <<<"$line")
            if ! grep -q '=>' <<<"$line"; then continue; fi
            path=$(awk '{print $3}' <<<"$line")
            [ -z "$path" ] || [ "$path" = "not" ] || [ ! -e "$path" ] && continue

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

    if command -v patchelf >/dev/null 2>&1; then
        for f in "$DIST_DIR"/lib*.so*; do
            [ -e "$f" ] || continue
            [ -L "$f" ] && continue
            patchelf --set-rpath '$ORIGIN' "$f" 2>/dev/null || true
            if readelf -lW "$f" 2>/dev/null | grep "GNU_STACK" | grep -q "RWE"; then
                echo "Clearing executable stack on $f" >&2
                patchelf --clear-execstack "$f" || {
                    echo "ERROR: $f requires an executable stack and patchelf could not clear it" >&2
                    exit 1
                }
            fi
        done
    else
        echo "WARNING: patchelf not installed - transitive deps may not resolve at runtime" >&2
    fi

    bash "$(dirname "${BASH_SOURCE[0]}")/check-no-execstack.sh" "$DIST_DIR"
    bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-hermetic.sh" "$DIST_DIR"
}

bundle_macos() {
    local bridge="${BUNDLE_ROOT:-$DIST_DIR/libjpdfium.dylib}"
    [ -f "$bridge" ] || { echo "no libjpdfium.dylib found to bundle"; return 0; }

    local rpath_dirs=(/opt/homebrew/lib /usr/local/lib /usr/local/opt/icu4c/lib)
    local rpath_globs=(/opt/homebrew/opt/*/lib /usr/local/opt/*/lib)

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

    echo "Signing dylibs with identity: $identity"
    local f
    for f in "$DIST_DIR"/*.dylib; do
        [ -L "$f" ] && continue
        [ -e "$f" ] || continue
        codesign --force --timestamp --options runtime \
                 --sign "$identity" "${keychain_args[@]}" "$f"
        codesign --verify --strict --verbose=2 "$f"
    done
}

bundle_windows() {
    local bridge="${BUNDLE_ROOT:-$DIST_DIR/jpdfium.dll}"
    [ -f "$bridge" ] || { echo "no jpdfium.dll found to bundle"; return 0; }

    if ! command -v dumpbin >/dev/null 2>&1; then
        local vs_dumpbin
        vs_dumpbin=$(find "/c/Program Files/Microsoft Visual Studio" \
                     -name 'dumpbin.exe' 2>/dev/null | head -1 || true)
        if [ -z "$vs_dumpbin" ]; then
            echo "ERROR: dumpbin not found" >&2
            return 1
        fi
        DUMPBIN="$vs_dumpbin"
    else
        DUMPBIN=dumpbin
    fi

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

    local queue=("$bridge")
    while [ "${#queue[@]}" -gt 0 ]; do
        local target="${queue[0]}"
        queue=("${queue[@]:1}")
        local deps
        deps=$("$DUMPBIN" //dependents "$target" 2>/dev/null \
               | grep -oE '[A-Za-z0-9_+.-]+\.[Dd][Ll][Ll]' \
               | sort -u || true)
        while IFS= read -r dep; do
            [ -z "$dep" ] && continue
            if is_system_dll "$dep"; then continue; fi
            local dest="$DIST_DIR/$dep"
            if [ -e "$dest" ]; then continue; fi
            local src
            src=$(find_dll "$dep" || true)
            if [ -z "$src" ]; then
                continue
            fi
            cp -v "$src" "$dest"
            queue+=("$dest")
        done <<<"$deps"
    done

    local arch_dir
    case "$PLATFORM" in
        windows-arm64|vips-windows-arm64) arch_dir="arm64" ;;
        *) arch_dir="x64" ;;
    esac
    local redist_dir
    redist_dir=$(find "/c/Program Files/Microsoft Visual Studio" -path "*VC/Redist/MSVC/*/${arch_dir}/Microsoft.VC*.CRT" -type d 2>/dev/null | sort | tail -1 || true)
    if [ -n "$redist_dir" ]; then
        local work_dir
        work_dir=$(mktemp -d 2>/dev/null || mktemp -d -t 'crt-imports')
        local crt_imports="$work_dir/crt-imports.txt"
        : > "$crt_imports"
        for dll in "$DIST_DIR"/*.dll; do
            [ -e "$dll" ] || continue
            "$DUMPBIN" //dependents "$dll" 2>/dev/null \
                | grep -oiE '(msvcp140|vcruntime140|concrt140)[a-z0-9_]*\.dll' \
                >> "$crt_imports" || true
        done
        sort -u "$crt_imports" | while IFS= read -r crt; do
            [ -z "$crt" ] && continue
            [ -e "$DIST_DIR/$crt" ] && continue
            if [ -f "$redist_dir/$crt" ]; then
                cp -v "$redist_dir/$crt" "$DIST_DIR/$crt"
            fi
        done
        rm -rf "$work_dir"
    fi
}

case "$PLATFORM" in
    linux-*|vips-linux-*)
        bundle_linux
        find "$DIST_DIR" -maxdepth 1 -type f -name '*allocator_shim*' -print -delete
        if command -v strip >/dev/null 2>&1; then
            strip --strip-unneeded "$DIST_DIR/libjpdfium.so" 2>/dev/null || true
            for f in "$DIST_DIR"/lib*.so "$DIST_DIR"/lib*.so.*; do
                [ -L "$f" ] && continue
                [ -e "$f" ] || continue
                strip --strip-unneeded "$f" 2>/dev/null || true
            done
        fi
        ;;
    darwin-*|vips-darwin-*)
        bundle_macos
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
        find "$DIST_DIR" -maxdepth 1 -type f \
            \( -name '*allocator_shim*' -o -name '*raw_ptr*' \) -print -delete
        ;;
    *)
        echo "Unknown platform: $PLATFORM" >&2
        exit 1
        ;;
esac

bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-orphans.sh" "$DIST_DIR" "$PLATFORM"
bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-lean.sh" "$DIST_DIR" "$PLATFORM"
bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-size.sh" "$DIST_DIR" "$PLATFORM"

echo "Final bundle contents for $PLATFORM:"
ls -la "$DIST_DIR/"
