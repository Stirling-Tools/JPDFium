#!/usr/bin/env bash
# Non-publishing ProGuard size experiment for the two library jars.
#
# Safe shrink-only ProGuard is intentionally not used for publication: it
# removes module-info and saves little, while optimized/obfuscated output
# changes public signatures or reflection/native-image metadata. This script
# makes the measured trade-off visible in CI without affecting release jars.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PROGUARD_VERSION="${PROGUARD_VERSION:-7.10.0}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -z "${PROGUARD_HOME:-}" ]; then
    curl -fsSL --max-time 120 --retry 5 --retry-all-errors --retry-delay 3 \
        "https://github.com/Guardsquare/proguard/releases/download/v${PROGUARD_VERSION}/proguard-${PROGUARD_VERSION}.zip" \
        -o "$WORK/proguard.zip"
    unzip -q "$WORK/proguard.zip" -d "$WORK"
    PROGUARD_HOME="$(find "$WORK" -maxdepth 1 -type d -name 'proguard-*' | head -n1)"
fi

JDK="${PROGUARD_JDK:-${JAVA_HOME:-}}"
if [ -z "$JDK" ] || [ ! -d "$JDK/jmods" ]; then
    echo "SKIP: ProGuard experiment needs a full JDK with jmods (set PROGUARD_JDK)"
    exit 0
fi
PROGUARD_JAR="$PROGUARD_HOME/lib/proguard.jar"
[ -f "$PROGUARD_JAR" ] || { echo "ERROR: $PROGUARD_JAR missing" >&2; exit 1; }

./gradlew :jpdfium:jar :jpdfium-vips:jar --no-daemon

for module in jpdfium jpdfium-vips; do
    JAR="$(find "$module/build/libs" -maxdepth 1 -name "$module-*.jar" \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n1)"
    [ -n "$JAR" ] || { echo "ERROR: no $module jar" >&2; exit 1; }
    OUT="$ROOT/$module/build/proguard/$(basename "${JAR%.jar}")-proguard.jar"
    JAR="$ROOT/$JAR"
    mkdir -p "$(dirname "$OUT")"
    CFG="$WORK/$module.pro"
    cat config/proguard-library.pro >"$CFG"
    {
        echo "-injars $JAR"
        echo "-outjars $OUT"
        for jmod in "$JDK"/jmods/*.jmod; do
            echo "-libraryjars $jmod"
        done
    } >>"$CFG"
    java -cp "$PROGUARD_JAR" proguard.ProGuard "@$CFG"
    echo "ProGuard $module: $(wc -c <"$JAR") -> $(wc -c <"$OUT") bytes"
done
