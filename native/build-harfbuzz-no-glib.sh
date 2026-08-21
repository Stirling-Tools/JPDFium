#!/usr/bin/env bash
# Rebuild libharfbuzz without GLib to drop libglib-2.0.{so.0,0.dylib} from
# the Linux + macOS bundles. Ubuntu's apt libharfbuzz0b and brew's
# harfbuzz both link against libglib for the hb-glib bindings (and icu /
# coretext bindings on macOS) we don't use - the bridge calls plain hb_*
# C APIs and PDFium has its own embedded harfbuzz, so the system one is
# only ever used for the bridge's font-shaping needs.
#
# Output:
#   Linux  - /usr/local/lib/libharfbuzz.so.*           (pkg-config picks
#                                                      this up ahead of
#                                                      /usr/lib)
#   macOS  - $(brew --prefix)/lib/libharfbuzz.*.dylib  (replaces brew's
#                                                      installed copy)
#
# Both platforms then move the original (apt/brew) harfbuzz libraries
# aside so the bundler's ldd/otool walk resolves to /usr/local or brew's
# refreshed copy.
#
# Skips silently with exit 0 if anything fails - bundle falls back to
# the original glib-linked harfbuzz.

echo "build-harfbuzz-no-glib.sh: start  ($(uname -s) $(uname -m))"

set -u

case "$(uname -s)" in
    Linux*)
        OS=linux
        PREFIX=/usr/local
        ;;
    Darwin*)
        OS=darwin
        # macos-14 matrix runs both darwin-arm64 (native, brew at
        # /opt/homebrew) and darwin-x64 (Rosetta cross-compile, brew at
        # /usr/local). Match the workflow's CMAKE_OSX_ARCHITECTURES /
        # PKG_CONFIG_PATH choice so meson finds x86_64 deps under
        # /usr/local for the cross-compile build instead of arm64 deps
        # at /opt/homebrew.
        if [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
            PREFIX=/usr/local
            if [ ! -x /usr/local/bin/brew ]; then
                echo "build-harfbuzz-no-glib.sh: x86_64 brew (Rosetta) not installed; skipping" >&2
                exit 0
            fi
        else
            if ! command -v brew >/dev/null 2>&1; then
                echo "build-harfbuzz-no-glib.sh: brew not on PATH on macOS; skipping" >&2
                exit 0
            fi
            PREFIX=$(brew --prefix)
        fi
        ;;
    *)
        echo "build-harfbuzz-no-glib.sh: skipping on $(uname -s)"
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

if [ -z "${HB_TAG:-}" ]; then
    HB_TAG="$(resolve_latest_tag harfbuzz/harfbuzz)"
    HB_TAG="${HB_TAG:-14.3.1}"
    echo "==> build-harfbuzz-no-glib.sh: harfbuzz resolved to latest: $HB_TAG"
else
    echo "==> build-harfbuzz-no-glib.sh: harfbuzz pinned by env: $HB_TAG"
fi
HB_REPO=https://github.com/harfbuzz/harfbuzz

# Build prereqs.
for tool in meson ninja pkg-config; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        if [ "$OS" = "darwin" ]; then
            echo "build-harfbuzz-no-glib.sh: $tool missing - brew install $tool" >&2
            brew install "$tool" 2>&1 | tail -3 || {
                echo "build-harfbuzz-no-glib.sh: brew install $tool failed; skipping" >&2
                exit 0
            }
        else
            echo "build-harfbuzz-no-glib.sh: $tool not found (apt install meson ninja-build)" >&2
            exit 0
        fi
    fi
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "Cloning harfbuzz ${HB_TAG}..."
if ! git clone --depth 1 -b "$HB_TAG" "$HB_REPO" "$WORK/harfbuzz" 2>&1 | tail -3; then
    echo "build-harfbuzz-no-glib.sh: git clone failed; skipping" >&2
    exit 0
fi

# When cross-compiling on macos-14 (arm64 host -> x86_64 target), meson
# doesn't honor CMAKE_OSX_ARCHITECTURES (cmake-only env var). It defaults
# to the host arch, then fails at link time because deps from /usr/local
# are x86_64 but the build is producing arm64. Write a meson cross-file
# that tells meson to invoke clang with -arch x86_64 and resolve deps via
# /usr/local/bin/pkg-config.
MESON_CROSS_FLAGS=()
if [ "$OS" = "darwin" ] && [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
    cat >"$WORK/macos-x64-cross.ini" <<'CROSS'
[binaries]
c = ['clang', '-arch', 'x86_64']
cpp = ['clang++', '-arch', 'x86_64']
objc = ['clang', '-arch', 'x86_64']
objcpp = ['clang++', '-arch', 'x86_64']
ar = 'ar'
strip = 'strip'
pkg-config = '/usr/local/bin/pkg-config'

[host_machine]
system = 'darwin'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
CROSS
    MESON_CROSS_FLAGS=(--cross-file "$WORK/macos-x64-cross.ini")
fi

# Configure with all binding options OFF - pure hb_* C API only.
# ${ARRAY[@]+...} guard: macOS bash 3.2 expands "${MESON_CROSS_FLAGS[@]}"
# under `set -u` as unbound when the array is empty (no-cross-file branch).
# The +alternate form expands to the array contents only when ARRAY is set.
meson setup "$WORK/harfbuzz/build" "$WORK/harfbuzz" \
    ${MESON_CROSS_FLAGS[@]+"${MESON_CROSS_FLAGS[@]}"} \
    --prefix="$PREFIX" \
    --buildtype=release \
    --default-library=static \
    -Db_staticpic=true \
    -Dsubset=enabled \
    -Dglib=disabled \
    -Dgobject=disabled \
    -Dicu=disabled \
    -Dgraphite=disabled \
    -Dcoretext=disabled \
    -Ddirectwrite=disabled \
    -Dgdi=disabled \
    -Dintrospection=disabled \
    -Ddocs=disabled \
    -Dtests=disabled \
    -Dbenchmark=disabled \
    -Dutilities=disabled \
    -Dexperimental_api=false \
  || { echo "build-harfbuzz-no-glib.sh: meson configure failed; skipping" >&2; exit 0; }

ninja -C "$WORK/harfbuzz/build" \
  || { echo "build-harfbuzz-no-glib.sh: ninja build failed; skipping" >&2; exit 0; }

# Linux uses sudo (GH runner). macOS owns brew prefix as the runner user,
# so plain `ninja install` works there (and `sudo` would prompt).
if [ "$OS" = "linux" ]; then
    sudo ninja -C "$WORK/harfbuzz/build" install \
      || { echo "build-harfbuzz-no-glib.sh: ninja install failed; skipping" >&2; exit 0; }
    sudo ldconfig 2>/dev/null || true
else
    ninja -C "$WORK/harfbuzz/build" install \
      || { echo "build-harfbuzz-no-glib.sh: ninja install failed; skipping" >&2; exit 0; }
fi

# Diagnostics - confirm static harfbuzz archives exist.
if [ "$OS" = "linux" ]; then
    NEW_HB=$(find "$PREFIX/lib" -maxdepth 2 -name "libharfbuzz.a" -type f 2>/dev/null | head -1)
    if [ -n "$NEW_HB" ]; then
        echo "Installed: $NEW_HB ($(du -h "$NEW_HB" | cut -f1))"
        echo "SUCCESS: static libharfbuzz built and installed."
    fi
else
    NEW_HB=$(find "$PREFIX/lib" -maxdepth 1 -name "libharfbuzz.a" -type f 2>/dev/null | head -1)
    if [ -n "$NEW_HB" ]; then
        echo "Installed: $NEW_HB ($(du -h "$NEW_HB" | cut -f1))"
        echo "SUCCESS: static libharfbuzz built and installed."
    fi
fi

# Move old shared harfbuzz aside so CMake / pkg-config links the static archives.
# Linux: apt's libs under /usr/lib/<triplet>/ and any /usr/local/lib shared copies.
# macOS: brew's shared dylibs under $(brew --prefix)/lib/ and Cellar.
if [ "$OS" = "linux" ]; then
    for old in /usr/lib/x86_64-linux-gnu/libharfbuzz.so* \
               /usr/lib/x86_64-linux-gnu/libharfbuzz-subset.so* \
               /usr/lib/aarch64-linux-gnu/libharfbuzz.so* \
               /usr/lib/aarch64-linux-gnu/libharfbuzz-subset.so* \
               /usr/local/lib/libharfbuzz.so* \
               /usr/local/lib/libharfbuzz-subset.so* \
               /usr/local/lib/x86_64-linux-gnu/libharfbuzz.so* \
               /usr/local/lib/x86_64-linux-gnu/libharfbuzz-subset.so* \
               /usr/local/lib/aarch64-linux-gnu/libharfbuzz.so* \
               /usr/local/lib/aarch64-linux-gnu/libharfbuzz-subset.so*; do
        [ -e "$old" ] || continue
        sudo mv "$old" "${old}.disabled" 2>/dev/null || true
    done
fi
