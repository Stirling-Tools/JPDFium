#!/usr/bin/env bash
# Rebuild libharfbuzz without GLib to drop libglib-2.0.so.0 (1.4 MB) from
# the Linux bundle. Ubuntu's apt libharfbuzz0b links against libglib for
# the hb-glib bindings (icu / coretext bindings too, but we don't pull
# those). We don't use hb-glib from the bridge — PDFium has its own
# embedded harfbuzz, and the bridge's HarfBuzz usage is plain hb_*.
#
# Output: /usr/local/lib/libharfbuzz.so.* — pkg-config picks this up
# ahead of /usr/lib via the default search order. Moves apt's harfbuzz
# .so aside afterwards so ldd resolves /usr/local.
#
# Usage: build-harfbuzz-no-glib.sh        # Linux only

echo "build-harfbuzz-no-glib.sh: start  ($(uname -s) $(uname -m))"

case "$(uname -s)" in
    Linux*) ;;
    *) echo "build-harfbuzz-no-glib.sh: skipping on $(uname -s)"; exit 0;;
esac

set -u

HB_TAG="${HB_TAG:-8.3.0}"   # match the 8.3.x series Ubuntu Noble ships
HB_REPO=https://github.com/harfbuzz/harfbuzz

# Build prereqs already installed by the main apt step.
for tool in meson ninja pkg-config; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "build-harfbuzz-no-glib.sh: $tool not found (apt install meson ninja-build)" >&2
        exit 0
    fi
done

WORK=$(mktemp -d)
trap "rm -rf '$WORK'" EXIT

echo "Cloning harfbuzz ${HB_TAG}..."
if ! git clone --depth 1 -b "$HB_TAG" "$HB_REPO" "$WORK/harfbuzz" 2>&1 | tail -3; then
    echo "build-harfbuzz-no-glib.sh: git clone failed; skipping" >&2
    exit 0
fi

# Configure with all binding options OFF — pure hb_* C API only.
meson setup "$WORK/harfbuzz/build" "$WORK/harfbuzz" \
    --prefix=/usr/local \
    --buildtype=release \
    --default-library=shared \
    -Dglib=disabled \
    -Dgobject=disabled \
    -Dicu=disabled \
    -Dgraphite=disabled \
    -Dcoretext=disabled \
    -Ddirectwrite=disabled \
    -Duniscribe=disabled \
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

sudo ninja -C "$WORK/harfbuzz/build" install \
  || { echo "build-harfbuzz-no-glib.sh: ninja install failed; skipping" >&2; exit 0; }
sudo ldconfig 2>/dev/null || true

# Diagnostics — confirm no libglib in the new libharfbuzz.
NEW_HB=$(find /usr/local/lib -maxdepth 1 -name "libharfbuzz.so.*" -type f 2>/dev/null | head -1)
if [ -n "$NEW_HB" ]; then
    echo "Installed: $NEW_HB ($(du -h "$NEW_HB" | cut -f1))"
    echo "ldd:"
    ldd "$NEW_HB" | sed 's/^/  /'
    if ldd "$NEW_HB" | grep -q 'libglib'; then
        echo "WARNING: new harfbuzz still links libglib — bindings flag wasn't honored?" >&2
    fi
fi

# Move apt's harfbuzz .so files aside so the bundler picks up /usr/local first.
for old in /usr/lib/x86_64-linux-gnu/libharfbuzz.so* \
           /usr/lib/x86_64-linux-gnu/libharfbuzz-subset.so* \
           /usr/lib/aarch64-linux-gnu/libharfbuzz.so* \
           /usr/lib/aarch64-linux-gnu/libharfbuzz-subset.so*; do
    [ -e "$old" ] || continue
    sudo mv "$old" "${old}.disabled" 2>/dev/null || true
done
