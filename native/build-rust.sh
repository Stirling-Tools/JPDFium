#!/usr/bin/env bash
# Build libjpdfium_rust.a - the Rust static library for JPDFium.
#
# Provides four C-ABI functions linked directly into libjpdfium.so:
#   jpdfium_rust_compress_pdf  - lopdf + zopfli superior FlateDecode compression
#   jpdfium_rust_repair_lopdf  - lopdf tolerant XRef rebuild (final repair stage)
#   jpdfium_rust_resize_pixels - fast_image_resize SIMD pixel scaling (Lanczos3)
#   jpdfium_rust_compress_png  - oxipng lossless PNG optimisation
#
# Called automatically by build-real.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUST_DIR="${SCRIPT_DIR}/rust"
OUTPUT="${RUST_DIR}/target/release/libjpdfium_rust.a"

# ── Check / install Rust ────────────────────────────────────────────────────
if ! command -v cargo &>/dev/null; then
    echo "cargo not found - installing Rust via rustup..."
    # Pin rustup-init to a specific release (RUSTUP_VERSION) and a pinned default
    # toolchain instead of piping an unpinned installer straight into sh.
    # The installer binary is downloaded, checksum-verified, then executed.
    RUSTUP_VERSION="1.29.0"
    RUST_TOOLCHAIN="1.97.1"

    case "$(uname -s 2>/dev/null)" in
        Linux)
            case "$(uname -m)" in
                aarch64|arm64) RUSTUP_TRIPLE="aarch64-unknown-linux-gnu"; RUSTUP_SHA256="9732d6c5e2a098d3521fca8145d826ae0aaa067ef2385ead08e6feac88fa5792" ;;
                *)             RUSTUP_TRIPLE="x86_64-unknown-linux-gnu";   RUSTUP_SHA256="4acc9acc76d5079515b46346a485974457b5a79893cfb01112423c89aeb5aa10" ;;
            esac
            RUSTUP_INIT="rustup-init"
            ;;
        Darwin)
            case "$(uname -m)" in
                aarch64|arm64) RUSTUP_TRIPLE="aarch64-apple-darwin"; RUSTUP_SHA256="aeb4105778ca1bd3c6b0e75768f581c656633cd51368fa61289b6a71696ac7e1" ;;
                *)             RUSTUP_TRIPLE="x86_64-apple-darwin";   RUSTUP_SHA256="33cf85df9142bc6d29cbc62fa5ca1d4c29622cddb55213a4c1a43c457fb9b2d7" ;;
            esac
            RUSTUP_INIT="rustup-init"
            ;;
        *)
            # MSYS/Git-Bash on Windows ships x86_64-pc-windows-msvc binaries.
            RUSTUP_TRIPLE="x86_64-pc-windows-msvc"
            RUSTUP_SHA256="86478e53f769379d7f0ebfa7c9aa97cb76ca92233f79aa2cc0dbee2efaac73c7"
            RUSTUP_INIT="rustup-init.exe"
            ;;
    esac

    RUSTUP_TMP="${TMPDIR:-/tmp}/${RUSTUP_INIT}"
    curl --proto '=https' --tlsv1.2 -fsSL --retry 5 --retry-delay 3 \
        "https://static.rust-lang.org/rustup/archive/${RUSTUP_VERSION}/${RUSTUP_TRIPLE}/${RUSTUP_INIT}" \
        -o "${RUSTUP_TMP}"
    ACTUAL_SHA256=$(sha256sum "${RUSTUP_TMP}" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "${RUSTUP_TMP}" | cut -d' ' -f1)
    if [ "${ACTUAL_SHA256}" != "${RUSTUP_SHA256}" ]; then
        echo "ERROR: rustup-init checksum mismatch (expected ${RUSTUP_SHA256}, got ${ACTUAL_SHA256})" >&2
        exit 1
    fi
    chmod +x "${RUSTUP_TMP}"
    "${RUSTUP_TMP}" -y --default-toolchain "${RUST_TOOLCHAIN}" --profile minimal
    rm -f "${RUSTUP_TMP}"
    # shellcheck disable=SC1090
    source "${HOME}/.cargo/env"
fi

CARGO_VERSION=$(cargo --version)
echo "Using ${CARGO_VERSION}"

# Retry transient network failures (DNS timeouts, connection resets on CI
# runners) instead of failing the entire native build on the first hiccup.
# Cargo's own net.retry in .cargo/config.toml handles per-request retries;
# this wrapper covers whole-build failures that abort before any retry kicks in.
run_cargo_build() {
    local tries=3 delay=15
    local i
    for i in $(seq 1 "$tries"); do
        if cargo build --release "$@"; then
            return 0
        fi
        if [ "$i" -lt "$tries" ]; then
            echo "cargo build failed (attempt $i/$tries) - retrying in ${delay}s..." >&2
            sleep "$delay"
            delay=$((delay * 2))
        fi
    done
    return 1
}

# ── Build ───────────────────────────────────────────────────────────────────
echo "Building Rust static library (release)..."
cd "${RUST_DIR}"

# darwin-x64 cross-compile: the macOS x64 publish/snapshot leg runs on an
# arm64 (macos-14) host with CMAKE_OSX_ARCHITECTURES=x86_64, so the C++ bridge
# is compiled for x86_64. Build the Rust staticlib for that same arch - an
# arm64 libjpdfium_rust.a would be silently dropped at link time
# ("found architecture arm64, required architecture x86_64") and leave the
# jpdfium_rust_* symbols undefined.
CROSS_TARGET=""
if [ -n "${CMAKE_OSX_ARCHITECTURES:-}" ]; then
    case "$(uname -m)" in
        x86_64)        HOST_TRIPLE="x86_64-apple-darwin" ;;
        arm64|aarch64) HOST_TRIPLE="aarch64-apple-darwin" ;;
        *)             HOST_TRIPLE="" ;;
    esac
    DESIRED_TRIPLE=""
    case "${CMAKE_OSX_ARCHITECTURES}" in
        x86_64)        DESIRED_TRIPLE="x86_64-apple-darwin" ;;
        arm64|aarch64) DESIRED_TRIPLE="aarch64-apple-darwin" ;;
    esac
    if [ -n "${DESIRED_TRIPLE}" ] && [ "${DESIRED_TRIPLE}" != "${HOST_TRIPLE}" ]; then
        CROSS_TARGET="${DESIRED_TRIPLE}"
    fi
fi

if [ -n "${CROSS_TARGET}" ]; then
    echo "Cross-compiling Rust staticlib for ${CROSS_TARGET}..."
    if command -v rustup >/dev/null 2>&1; then
        rustup target add "${CROSS_TARGET}"
    fi
    run_cargo_build --target "${CROSS_TARGET}"
    # Cargo writes target-specific artifacts under target/<triple>/; the
    # bridge expects the lib at the canonical target/release path, so copy it.
    mkdir -p "$(dirname "${OUTPUT}")"
    cp "${RUST_DIR}/target/${CROSS_TARGET}/release/libjpdfium_rust.a" "${OUTPUT}"
else
    run_cargo_build
fi

echo ""
echo "Built: ${OUTPUT}"
