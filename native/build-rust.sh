#!/usr/bin/env bash
# Build libjpdfium_rust.a — the Rust static library for JPDFium.
#
# Provides four C-ABI functions linked directly into libjpdfium.so:
#   jpdfium_rust_compress_pdf  — lopdf + zopfli superior FlateDecode compression
#   jpdfium_rust_repair_lopdf  — lopdf tolerant XRef rebuild (final repair stage)
#   jpdfium_rust_resize_pixels — fast_image_resize SIMD pixel scaling (Lanczos3)
#   jpdfium_rust_compress_png  — oxipng lossless PNG optimisation
#
# Called automatically by build-real.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUST_DIR="${SCRIPT_DIR}/rust"
OUTPUT="${RUST_DIR}/target/release/libjpdfium_rust.a"

# ── Check / install Rust ────────────────────────────────────────────────────
if ! command -v cargo &>/dev/null; then
    echo "cargo not found — installing Rust via rustup..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
    # shellcheck disable=SC1090
    source "${HOME}/.cargo/env"
fi

CARGO_VERSION=$(cargo --version)
echo "Using ${CARGO_VERSION}"

# ── Build ───────────────────────────────────────────────────────────────────
echo "Building Rust static library (release)..."
cd "${RUST_DIR}"
cargo build --release

echo ""
echo "Built: ${OUTPUT}"
