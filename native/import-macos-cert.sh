#!/usr/bin/env bash
# Imports an Apple Developer ID certificate into a temporary keychain for codesigning.
set -euo pipefail

: "${MACOS_CERTIFICATE_P12_BASE64:?MACOS_CERTIFICATE_P12_BASE64 required}"
: "${MACOS_CERTIFICATE_PASSWORD:?MACOS_CERTIFICATE_PASSWORD required}"

TMP_DIR="${RUNNER_TEMP:-/tmp}"
KEYCHAIN_PATH="$TMP_DIR/jpdfium-signing.keychain-db"
KEYCHAIN_PASSWORD="${MACOS_KEYCHAIN_PASSWORD:-jpdfium-ci-keychain}"
CERT_PATH="$TMP_DIR/jpdfium-developer-id.p12"

cleanup() { rm -f "$CERT_PATH"; }
trap cleanup EXIT

echo "$MACOS_CERTIFICATE_P12_BASE64" | base64 --decode > "$CERT_PATH"

security delete-keychain "$KEYCHAIN_PATH" 2>/dev/null || true
security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
security set-keychain-settings -lut 21600 "$KEYCHAIN_PATH"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"

# Import cert + private key; -T lets codesign/security use the key unattended.
security import "$CERT_PATH" -P "$MACOS_CERTIFICATE_PASSWORD" \
    -A -t cert -f pkcs12 -k "$KEYCHAIN_PATH" \
    -T /usr/bin/codesign -T /usr/bin/security

# Authorise codesign to use the key without an interactive prompt.
security set-key-partition-list -S apple-tool:,apple: \
    -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH" >/dev/null

# Make our keychain the one codesign searches.
security list-keychains -d user -s "$KEYCHAIN_PATH"

# Resolve the identity to sign with.
IDENTITY="${MACOS_SIGN_IDENTITY:-}"
if [ -z "$IDENTITY" ]; then
    IDENTITY=$(security find-identity -v -p codesigning "$KEYCHAIN_PATH" \
               | grep 'Developer ID Application' | head -1 \
               | sed -E 's/^[[:space:]]*[0-9]+\)[[:space:]]+[0-9A-Fa-f]+[[:space:]]+"(.*)"$/\1/')
fi
if [ -z "$IDENTITY" ]; then
    echo "ERROR: no 'Developer ID Application' identity in the imported cert." >&2
    echo "Identities present:" >&2
    security find-identity -v -p codesigning "$KEYCHAIN_PATH" >&2 || true
    exit 1
fi
echo "Using signing identity: $IDENTITY"

if [ -n "${GITHUB_ENV:-}" ]; then
    {
        echo "MACOS_SIGN_IDENTITY=$IDENTITY"
        echo "MACOS_KEYCHAIN_PATH=$KEYCHAIN_PATH"
    } >> "$GITHUB_ENV"
fi
