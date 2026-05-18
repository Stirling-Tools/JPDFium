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

    # libicuuc et al. use SONAME like libicuuc.so.74 with a symlink to the
    # versioned file. ldd resolves to the symlink target, but the bridge's
    # NEEDED entry refers to the SONAME. Make sure both names are present.
    for f in "$DIST_DIR"/*.so.*; do
        [ -e "$f" ] || continue
        local short="${f%.so.*}.so"
        if [ ! -e "$short" ] && [ -e "$f" ]; then
            cp "$f" "$short"
        fi
    done

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

    # Codesign-adhoc each bundled dylib so macOS' loader doesn't reject them
    # in hardened-runtime / notarized contexts. Adhoc is enough; downstream
    # bundlers (Tauri) can re-sign with their own identity at packaging time.
    for f in "$DIST_DIR"/*.dylib; do
        [ -e "$f" ] || continue
        codesign --force --sign - "$f" 2>/dev/null || true
    done
}

case "$PLATFORM" in
    linux-*)  bundle_linux ;;
    darwin-*) bundle_macos ;;
    windows-*)
        echo "Windows already bundles vcpkg DLLs in the Stage binaries step — nothing to do here."
        ;;
    *)
        echo "Unknown platform: $PLATFORM" >&2
        exit 1
        ;;
esac

echo ""
echo "Final bundle contents for $PLATFORM:"
ls -la "$DIST_DIR/"
