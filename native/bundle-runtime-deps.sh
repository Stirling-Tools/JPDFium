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

    local skip_regex='^(linux-vdso|libc|libc\.musl[^.]*|libm|libdl|libpthread|libgcc_s|libresolv|librt|libstdc\+\+)\.so|^ld-linux|^ld-musl'

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
            if readelf -lW "$f" 2>/dev/null | grep "GNU_STACK" | grep "RWE" >/dev/null; then
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
    bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-hermetic.sh" "$DIST_DIR" "$PLATFORM"
}

bundle_freebsd() {
    local bridge="${BUNDLE_ROOT:-$DIST_DIR/libjpdfium.so}"
    [ -f "$bridge" ] || { echo "no libjpdfium.so found to bundle"; return 0; }

    local sysroot="${FREEBSD_SYSROOT:-}"
    local -a search_dirs
    if [ -n "$sysroot" ]; then
        search_dirs=("$sysroot/usr/lib" "$DIST_DIR")
    else
        search_dirs=("/usr/lib" "/usr/local/lib" "$DIST_DIR")
    fi
    local skip_regex='^(libc|libm|libthr|libc\+\+|libcxxrt|libz|libelf|libexecinfo|libgcc_s|librt|libutil|libmd|libcrypt|libssp)\.so'

    declare -A visited=()
    local queue=("$bridge")
    visited["$(basename "$bridge")"]=1

    # Also enqueue any libraries already staged in DIST_DIR (e.g. libpdfium.so)
    for staged in "$DIST_DIR"/lib*.so "$DIST_DIR"/lib*.so.*; do
        [ -e "$staged" ] || continue
        [ -L "$staged" ] && continue
        local b
        b="$(basename "$staged")"
        if [ -z "${visited["$b"]:-}" ]; then
            visited["$b"]=1
            queue+=("$staged")
        fi
    done

    while [ "${#queue[@]}" -gt 0 ]; do
        local target="${queue[0]}"
        queue=("${queue[@]:1}")

        while IFS= read -r dep; do
            [ -z "$dep" ] && continue
            if echo "$dep" | grep -qE "$skip_regex"; then continue; fi

            local dest="$DIST_DIR/$dep"
            if [ ! -e "$dest" ]; then
                local found=""
                for dir in "${search_dirs[@]}"; do
                    if [ -e "$dir/$dep" ]; then
                        found="$dir/$dep"
                        break
                    fi
                done
                if [ -n "$found" ] && [ "$found" != "$dest" ]; then
                    cp -v "$found" "$dest"
                fi
            fi

            if [ -e "$dest" ] && [ -z "${visited["$dep"]:-}" ]; then
                visited["$dep"]=1
                queue+=("$dest")
            fi
        done < <(readelf -d "$target" 2>/dev/null | awk '/NEEDED/{gsub(/\[|\]/,"",$5); print $5}' || true)
    done

    if command -v patchelf >/dev/null 2>&1; then
        for f in "$DIST_DIR"/lib*.so*; do
            [ -e "$f" ] || continue
            [ -L "$f" ] && continue
            patchelf --set-rpath '$ORIGIN' "$f" 2>/dev/null || true
            if readelf -lW "$f" 2>/dev/null | grep "GNU_STACK" | grep "RWE" >/dev/null; then
                echo "Clearing executable stack on $f" >&2
                patchelf --clear-execstack "$f" || {
                    echo "ERROR: $f requires an executable stack and patchelf could not clear it" >&2
                    exit 1
                }
            fi
        done
    fi

    bash "$(dirname "${BASH_SOURCE[0]}")/check-no-execstack.sh" "$DIST_DIR"
    bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-hermetic.sh" "$DIST_DIR" "$PLATFORM"
}


bundle_macos() {
    local bridge="${BUNDLE_ROOT:-$DIST_DIR/libjpdfium.dylib}"
    [ -f "$bridge" ] || { echo "no libjpdfium.dylib found to bundle"; return 0; }

    # native/pdfium/lib is where the vips build links the component PDFium
    # prebuild; its own @rpath deps (icuuc, harfbuzz-ng, ...) live there too.
    local rpath_dirs=(/opt/homebrew/lib /usr/local/lib /usr/local/opt/icu4c/lib
                      "$(dirname "${BASH_SOURCE[0]}")/pdfium/lib")
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

            # If the referenced name is already bundled, keep it. This covers
            # pre-staged shadows: build-minimal-icu-macos.sh stages a trimmed
            # libicudata.<MAJ>.dylib ahead of time precisely so the bundler
            # picks it up instead of brew's full copy.
            local base
            base=$(basename "$dep")
            local dest="$DIST_DIR/$base"
            if [ -e "$dest" ]; then
                chmod u+w "$dest" 2>/dev/null || true
                install_name_tool -change "$orig_dep" "@loader_path/$base" "$target" 2>/dev/null || true
                continue
            fi

            # New name: converge Homebrew alias chains on the real file
            # (libicuuc.dylib -> libicuuc.78.dylib -> libicuuc.78.3.dylib).
            # A plain cp follows each alias into a full duplicate copy;
            # bundling one real file that every alias rewrites to avoids it.
            dep=$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$dep")
            [ -f "$dep" ] || continue
            base=$(basename "$dep")
            dest="$DIST_DIR/$base"
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

    # @rpath is only safe when one of the dylib's LC_RPATH entries resolves
    # the dependency to a bundled file (or a rewrite can replace it).
    rpath_resolves() {
        local file="$1" base="$2" rp dir
        while IFS= read -r rp; do
            case "$rp" in
                @loader_path) dir=. ;;
                @loader_path/*) dir="${rp#@loader_path/}" ;;
                *) continue ;;
            esac
            if [ -e "$DIST_DIR/$dir/$base" ]; then
                return 0
            fi
        done < <(otool -l "$file" 2>/dev/null \
            | awk '/cmd LC_RPATH/{getline; getline; print $2}')
        return 1
    }

    local failed=0
    for f in "$DIST_DIR"/*.dylib; do
        [ -e "$f" ] || continue
        while IFS= read -r dep; do
            case "$dep" in
                /System/*|/usr/lib/*) ;;
                @loader_path/*)
                    if [ ! -e "$DIST_DIR/${dep#@loader_path/}" ]; then
                        echo "FAIL: $(basename "$f") references missing $dep" >&2
                        failed=1
                    fi
                    ;;
                @executable_path/*)
                    echo "FAIL: $(basename "$f") uses unsupported @executable_path dependency $dep" >&2
                    failed=1
                    ;;
                @rpath/*)
                    local base
                    base=$(basename "$dep")
                    if [ "$base" = "$(basename "$f")" ]; then
                        continue
                    fi
                    if [ -e "$DIST_DIR/$base" ]; then
                        if ! rpath_resolves "$f" "$base"; then
                            chmod u+w "$f" 2>/dev/null || true
                            if ! install_name_tool -change "$dep" "@loader_path/$base" "$f" 2>/dev/null; then
                                echo "FAIL: $(basename "$f") could not resolve or rewrite $dep" >&2
                                failed=1
                            fi
                        fi
                    elif [ "$base" != "libjpdfium.dylib" ]; then
                        echo "FAIL: $(basename "$f") references missing $dep" >&2
                        failed=1
                    fi
                    ;;
                *)
                    [ "$(basename "$dep")" = "$(basename "$f")" ] && continue
                    echo "FAIL: $(basename "$f") has non-relocatable dependency $dep" >&2
                    failed=1
                    ;;
            esac
        done <<<"$(otool -L "$f" 2>/dev/null | tail -n +2 | awk '{print $1}')"
    done
    [ "$failed" -eq 0 ] || { echo "ERROR: macOS bundle is not hermetic" >&2; exit 1; }
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
    [ -f "$bridge" ] || { echo "ERROR: bundle root $bridge not found" >&2; return 1; }

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
    local vcpkg_root="${VCPKG_ROOT:-${VCPKG_INSTALLATION_ROOT:-C:/vcpkg}}"
    local search_dirs=(
        "$DIST_DIR"
        "$vcpkg_root/installed/$vcpkg_triplet/bin"
        "native/pdfium/lib"
    )
    if [ -n "${VIPS_WIN_DIST:-}" ] && [ -d "$VIPS_WIN_DIST/bin" ]; then
        search_dirs+=("$VIPS_WIN_DIST/bin")
    fi
    find_dll() {
        local name="$1"
        local d
        for d in "${search_dirs[@]}"; do
            if [ -f "$d/$name" ]; then echo "$d/$name"; return 0; fi
        done
        return 1
    }

    local -A visited=()
    local queue=()
    for f in "$DIST_DIR"/*.dll; do
        [ -e "$f" ] || continue
        queue+=("$f")
        local base
        base=$(basename "$f")
        visited["$base"]=1
    done
    [ "${#queue[@]}" -gt 0 ] || queue=("$bridge")

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
            if [ ! -e "$dest" ]; then
                local src
                src=$(find_dll "$dep" || true)
                if [ -z "$src" ]; then
                    continue
                fi
                cp -v "$src" "$dest"
            fi
            if [ -z "${visited["$dep"]:-}" ]; then
                visited["$dep"]=1
                queue+=("$dest")
            fi
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

# Cross legs have shipped a wrong-arch dependency before (darwin-x64 staged an
# arm64 libsharpyuv), so every staged binary must match the target arch.
assert_bundle_arch() {
    local unix_arch win_arch failed=0 f archs machine
    case "$PLATFORM" in
        *-arm64) unix_arch=arm64; win_arch=ARM64 ;;
        *-x64)   unix_arch=x86_64; win_arch=x64 ;;
        *) echo "FAIL: cannot derive arch from platform $PLATFORM" >&2; exit 1 ;;
    esac
    case "$PLATFORM" in
        linux-*|vips-linux-*|freebsd-*|vips-freebsd-*|openbsd-*) [ "$unix_arch" = arm64 ] && unix_arch=aarch64 ;;
    esac
    case "$PLATFORM" in
        darwin-*|vips-darwin-*)
            for f in "$DIST_DIR"/*.dylib; do
                [ -e "$f" ] || continue
                archs=$(lipo -archs "$f" 2>/dev/null || echo "unknown")
                case " $archs " in
                    *" $unix_arch "*) ;;
                    *) echo "FAIL: $(basename "$f") has arch [$archs], expected $unix_arch" >&2; failed=1 ;;
                esac
            done
            ;;
        linux-*|vips-linux-*|freebsd-*|vips-freebsd-*|openbsd-*)
            for f in "$DIST_DIR"/lib*.so "$DIST_DIR"/lib*.so.*; do
                [ -e "$f" ] || continue
                machine=$(readelf -h "$f" 2>/dev/null | awk -F: '/Machine:/{print $2}' | xargs)
                if { [ "$unix_arch" = x86_64 ] && printf '%s' "$machine" | grep -qi 'x86-64'; } ||
                   { [ "$unix_arch" = aarch64 ] && printf '%s' "$machine" | grep -qi 'aarch64'; }; then
                    :
                else
                    echo "FAIL: $(basename "$f") is [$machine], expected $unix_arch" >&2
                    failed=1
                fi
            done
            ;;
        windows-*|vips-windows-*)
            local db=""
            if command -v dumpbin >/dev/null 2>&1; then
                db=dumpbin
            else
                db=$(find "/c/Program Files/Microsoft Visual Studio" -name 'dumpbin.exe' 2>/dev/null | head -1 || true)
            fi
            if [ -z "$db" ]; then
                echo "FAIL: dumpbin not found - cannot verify Windows bundle architecture" >&2
                exit 1
            fi
            for f in "$DIST_DIR"/*.dll; do
                [ -e "$f" ] || continue
                machine=$("$db" //headers "$f" 2>/dev/null | grep -m1 "machine (" | sed -E 's/.*machine \(([^)]*)\).*/\1/')
                case "$win_arch:$machine" in
                    x64:x64|ARM64:ARM64) ;;
                    *) echo "FAIL: $(basename "$f") is [$machine], expected $win_arch" >&2; failed=1 ;;
                esac
            done
            ;;
    esac
    [ "$failed" -eq 0 ] || exit 1
}

# The source-built vips links the distro ICU, whose full data file (~30 MB)
# would dominate the bundle. Swap it for the core bundle's trimmed copy (same
# data, ~0.45 MB) before strip/sign/checks. The core dist for this platform is
# native/dist/<platform without the vips- prefix>.
swap_full_icudata() {
    case "$PLATFORM" in
        vips-*) ;;
        *) return 0 ;;
    esac
    local core_platform="${PLATFORM#vips-}"
    local trimmed=""
    for cand in "native/dist/$core_platform"/libicudata.* "native/dist/$core_platform"/icudt*.dll; do
        [ -e "$cand" ] || continue
        trimmed="$cand"
        break
    done
    [ -n "$trimmed" ] || return 0
    [ "$(wc -c < "$trimmed")" -lt 1572864 ] || return 0
    # Only swap same-major ICU versions: the vips build links the distro/
    # brew ICU, whose major must match the trimmed core data's.
    icu_major() {
        echo "$1" | sed -n 's/^[^0-9]*\([0-9][0-9]*\).*/\1/p'
    }
    local trimmed_major
    trimmed_major=$(icu_major "$(basename "$trimmed")")
    [ -n "$trimmed_major" ] || return 0
    local f
    for f in "$DIST_DIR"/libicudata.* "$DIST_DIR"/icudt*.dll; do
        [ -e "$f" ] || continue
        [ "$(wc -c < "$f")" -gt 1572864 ] || continue
        if [ "$(icu_major "$(basename "$f")")" != "$trimmed_major" ]; then
            echo "WARNING: not replacing $(basename "$f") with ICU $trimmed_major data" >&2
            continue
        fi
        cp -v "$trimmed" "$f"
    done
}

# PDFium ships its own ICU (libicuuc.dylib/.so, data compiled in). When the
# distro/brew ICU already in the bundle is the same major version, the two
# libraries are ABI-compatible and one copy is enough - rewrite PDFium's
# imports to the versioned distro library and drop the unversioned duplicate
# (~1.7 MB per bundle). A version mismatch leaves both copies alone.
dedupe_icu() {
    local unversioned="" versioned="" f
    case "$PLATFORM" in
        darwin-*|vips-darwin-*)
            unversioned="$DIST_DIR/libicuuc.dylib"
            for f in "$DIST_DIR"/libicuuc.*.dylib; do
                [ -f "$f" ] || continue
                case "$(basename "$f")" in libicuuc.[0-9]*.dylib) versioned="$f";; esac
                [ -n "$versioned" ] && break
            done
            [ -f "$unversioned" ] && [ -f "$versioned" ] || return 0
            local um vm
            um=$(nm -gU "$unversioned" 2>/dev/null | awk 'match($0, /_[0-9]+$/) { if (m == "") m = substr($0, RSTART, RLENGTH) } END { print m }')
            vm=$(nm -gU "$versioned" 2>/dev/null | awk 'match($0, /_[0-9]+$/) { if (m == "") m = substr($0, RSTART, RLENGTH) } END { print m }')
            if [ -z "$um" ] || [ "$um" != "$vm" ]; then
                echo "  ICU dedupe skipped: PDFium ${um:-?} vs bundle ${vm:-?}" >&2
                return 0
            fi
            for f in "$DIST_DIR"/*.dylib; do
                [ -f "$f" ] || continue
                install_name_tool -change "@loader_path/libicuuc.dylib" \
                    "@loader_path/$(basename "$versioned")" "$f" 2>/dev/null || true
                install_name_tool -change "@rpath/libicuuc.dylib" \
                    "@loader_path/$(basename "$versioned")" "$f" 2>/dev/null || true
            done
            for f in "$DIST_DIR"/*.dylib; do
                [ -f "$f" ] || continue
                [ "$f" = "$unversioned" ] && continue
                if otool -L "$f" 2>/dev/null | grep -E '@(loader_path|rpath)/libicuuc\.dylib' >/dev/null; then
                    echo "ERROR: failed to rewrite ICU dependency in $(basename "$f")" >&2
                    return 1
                fi
            done
            rm -f "$unversioned"
            echo "  Deduped PDFium ICU into $(basename "$versioned") (major ${um#_})"
            ;;
        linux-*|vips-linux-*)
            # Kept separate on Linux: PDFium's ICU bundle carries the data its
            # layout/font code expects. Ubuntu 26.04's matching major was not
            # enough - nativeSmokeTest SIGABRTed after the swap. macOS was
            # verified against 171 PDFs and keeps the dedupe below.
            return 0
            ;;
    esac
}

case "$PLATFORM" in
    linux-*|vips-linux-*)
        bundle_linux
        assert_bundle_arch
        swap_full_icudata
        dedupe_icu
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
    freebsd-*|vips-freebsd-*)
        bundle_freebsd
        assert_bundle_arch
        find "$DIST_DIR" -maxdepth 1 -type f -name '*allocator_shim*' -print -delete
        STRIP_BIN="strip"
        if command -v llvm-strip >/dev/null 2>&1; then
            STRIP_BIN="llvm-strip"
        fi
        if command -v "$STRIP_BIN" >/dev/null 2>&1; then
            "$STRIP_BIN" --strip-unneeded "$DIST_DIR/libjpdfium.so" 2>/dev/null || true
            for f in "$DIST_DIR"/lib*.so "$DIST_DIR"/lib*.so.*; do
                [ -L "$f" ] && continue
                [ -e "$f" ] || continue
                "$STRIP_BIN" --strip-unneeded "$f" 2>/dev/null || true
            done
        fi
        ;;
    darwin-*|vips-darwin-*)
        bundle_macos
        assert_bundle_arch
        swap_full_icudata
        dedupe_icu
        if command -v strip >/dev/null 2>&1; then
            # -x drops local symbols too (the static harfbuzz contributes
            # thousands); exports and the dynamic symbol table stay.
            strip -x "$DIST_DIR/libjpdfium.dylib" 2>/dev/null || true
            for f in "$DIST_DIR"/*.dylib; do
                [ -L "$f" ] && continue
                [ -e "$f" ] || continue
                strip -x "$f" 2>/dev/null || true
            done
        fi
        sign_macos
        ;;
    windows-*|vips-windows-*)
        bundle_windows
        assert_bundle_arch
        swap_full_icudata
        find "$DIST_DIR" -maxdepth 1 -type f \
            \( -name '*allocator_shim*' -o -name '*raw_ptr*' \) -print -delete
        if command -v llvm-strip >/dev/null 2>&1; then
            for f in "$DIST_DIR"/*.dll; do
                [ -e "$f" ] || continue
                llvm-strip --strip-unneeded "$f" 2>/dev/null || true
            done
        fi
        ;;
    *)
        echo "Unknown platform: $PLATFORM" >&2
        exit 1
        ;;
esac

# Stripping must not remove exports the JNI loader binds to.
SYMBOL_CHECK="$(dirname "${BASH_SOURCE[0]}")/../.github/scripts/check-native-symbols.sh"
if [ -f "$SYMBOL_CHECK" ]; then
    bash "$SYMBOL_CHECK" "$PLATFORM" "$DIST_DIR"
fi

bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-orphans.sh" "$DIST_DIR" "$PLATFORM"
bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-lean.sh" "$DIST_DIR" "$PLATFORM"
bash "$(dirname "${BASH_SOURCE[0]}")/check-bundle-size.sh" "$DIST_DIR" "$PLATFORM"

echo "Final bundle contents for $PLATFORM:"
ls -la "$DIST_DIR/"
