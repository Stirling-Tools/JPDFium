#!/usr/bin/env bash
# Verifies the JPDFium artifact set, then runs a smoke against it.
#   MODE=staging  Central Portal validated-deployment downloads (before release)
#   MODE=released repo1.maven.org (after the deployment is released)
# Called by release.yml with VERSION and PLATFORM in the environment.
set -euo pipefail

MODE="${MODE:?MODE must be staging or released}"
VERSION="${VERSION:?VERSION is required}"
PLATFORM="${PLATFORM:?PLATFORM is required}"
GROUP_PATH="com/stirling"

case "$MODE" in
  staging)
    REPO_URL="https://central.sonatype.com/api/v1/publisher/deployments/download"
    PORTAL_TOKEN="Bearer $(printf '%s:%s' "${CENTRAL_PORTAL_USERNAME:?}" "${CENTRAL_PORTAL_PASSWORD:?}" | base64)"
    ;;
  released)
    REPO_URL="https://repo1.maven.org/maven2"
    PORTAL_TOKEN=""
    ;;
  *)
    echo "ERROR: unknown MODE '$MODE'" >&2
    exit 2
    ;;
esac

echo "Verifying JPDFium $VERSION ($PLATFORM) from $MODE..."

# Every module that must be present, with whether it ships a jar. The BOM is a
# java-platform publication and has only pom + module metadata.
module_paths() {
  local module="$1" jar="$2"
  local base="${GROUP_PATH}/${module}/${VERSION}/${module}-${VERSION}"
  if [ "$jar" = yes ]; then
    echo "${base}.jar"
    echo "${base}.jar.asc"
    echo "${base}-sources.jar"
    echo "${base}-javadoc.jar"
  fi
  echo "${base}.pom"
  echo "${base}.pom.asc"
  echo "${base}.module"
}

EXPECTED=()
while read -r path; do EXPECTED+=("$path"); done < <(module_paths jpdfium yes)
while read -r path; do EXPECTED+=("$path"); done < <(module_paths jpdfium-spring yes)
while read -r path; do EXPECTED+=("$path"); done < <(module_paths jpdfium-vips yes)
while read -r path; do EXPECTED+=("$path"); done < <(module_paths jpdfium-bom no)
while read -r path; do EXPECTED+=("$path"); done < <(module_paths "jpdfium-natives-${PLATFORM}" yes)
while read -r path; do EXPECTED+=("$path"); done < <(module_paths "jpdfium-natives-vips-${PLATFORM}" yes)

fetch() {
  local path="$1" out="${2:-/dev/null}"
  if [ -n "$PORTAL_TOKEN" ]; then
    curl -fsSL -H "Authorization: ${PORTAL_TOKEN}" -o "$out" "${REPO_URL}/${path}"
  else
    curl -fsSL -o "$out" "${REPO_URL}/${path}"
  fi
}

# Staging deployments are validated asynchronously; Central needs index time.
MAX_ATTEMPTS=60
FOUND=0
FIRST="${EXPECTED[0]}"
for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  if fetch "$FIRST"; then
    echo "Found deployment (attempt $attempt)."
    FOUND=1
    break
  fi
  echo "Attempt $attempt: $FIRST not available yet, waiting 30s..."
  sleep 30
done
if [ "$FOUND" -ne 1 ]; then
  echo "ERROR: $FIRST did not become available in $MODE within $((MAX_ATTEMPTS * 30 / 60)) minutes."
  exit 1
fi

MISSING=0
for path in "${EXPECTED[@]}"; do
  if fetch "$path"; then
    echo "  [ok] $path"
  else
    echo "  [MISSING] $path"
    MISSING=$((MISSING + 1))
  fi
done
if [ "$MISSING" -ne 0 ]; then
  echo "ERROR: $MISSING expected artifacts are missing in $MODE."
  exit 1
fi
echo "All ${#EXPECTED[@]} artifacts present."

# Standalone consumer that resolves only from the verified repository.
WORK_DIR=$(mktemp -d 2>/dev/null || mktemp -d -t 'jpdfium-verify')
cd "$WORK_DIR"

cat << 'SETTINGS' > settings.gradle.kts
rootProject.name = "jpdfium-verify"
SETTINGS

cat << BUILD > build.gradle.kts
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication

plugins {
    application
}

repositories {
    if (System.getenv("VERIFY_STAGING") == "true") {
        maven {
            url = uri(System.getenv("VERIFY_REPO_URL") + "/")
            credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                value = System.getenv("VERIFY_TOKEN")
            }
            authentication { create<HttpHeaderAuthentication>("header") }
        }
    }
    mavenCentral()
}

dependencies {
    implementation("com.stirling:jpdfium:${VERSION}")
    implementation("com.stirling:jpdfium-vips:${VERSION}")
    runtimeOnly("com.stirling:jpdfium-natives-${PLATFORM}:${VERSION}")
    runtimeOnly("com.stirling:jpdfium-natives-vips-${PLATFORM}:${VERSION}")
}

application {
    mainClass.set("VerifyCentral")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
BUILD

mkdir -p src/main/java
cat << 'JAVA' > src/main/java/VerifyCentral.java
import java.util.Base64;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.vips.*;

public class VerifyCentral {
    private static final String PDF_BASE64 =
            "JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9QYWdlcyAyIDAgUgo+PgplbmRvYmoKOSAwIG9iago8PAovTGVuZ3RoIDQxCi9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJxzCuHSdzNUMDRSCEnjMjdSMDcwUAhJ4dJw1FQIyeJyDeECAHMSBs0NCmVuZHN0cmVhbQplbmRvYmoKMTAgMCBvYmoKPDwKL0xlbmd0aCAxODYKL1R5cGUgL09ialN0bQovTiA1Ci9GaWx0ZXIgL0ZsYXRlRGVjb2RlCi9GaXJzdCAyNwo+PgpzdHJlYW0NCnicVY7LCsJADEV/5X6BmenTQimoKIIIUgUXxUVtgwxIRpyp6N9LW5C6SOCeHJIEUAgRBYigwwwxdJxgDp1q5DmdPg8GHeobO9DOtA5VCIUSF9DKduKhURT/JmjPramX9o1KzRT6SnQwU0izvl967cniEQyrqGRnu2fDDtEIVlY8i3fIhjwe2FjxiKdAYz6J4wODRcfu6ofYQw1a1o7HyZbvL/amqUFraWxr5AY6G1mIMz9QFPgCJrlM0w0KZW5kc3RyZWFtCmVuZG9iagoxMSAwIG9iago8PAovTGVuZ3RoIDM1Ci9Sb290IDEgMCBSCi9JRCBbPDk3MTkwNUZEM0Y5RjhCNkY5REZCRjI1NkNENkYwMjMxPiA8OTcxOTA1RkQzRjlGOEI2RjlERkJGMjU2Q0Q2RjAyMzE+XQovVHlwZSAvWFJlZgovU2l6ZSAxMgovSW5kZXggWzAgNiA4IDNdCi9XIFsxIDEgMV0KL0ZpbHRlciAvRmxhdGVEZWNvZGUKPj4Kc3RyZWFtDQp4nGNg+M/Iz8DExcDExcjExcTExczExcLox8B4gAEAITgCZg0KZW5kc3RyZWFtCmVuZG9iagpzdGFydHhyZWYKNDgyCiUlRU9GCg==";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Verifying JPDFium + libvips natives ===");

        VipsNatives.configure();
        NativeLoader.ensureLoaded();

        VipsAvailability.State state = VipsAvailability.probe();
        System.out.println("Libvips available: " + state.available());
        System.out.println("Platform: " + state.platform());
        System.out.println("Version: " + state.version());
        System.out.println("Savers: PNG=" + state.pngsave() + ", JPEG=" + state.jpegsave()
                + ", WEBP=" + state.webpsave() + ", TIFF=" + state.tiffsave()
                + ", HEIF=" + state.heifsave() + ", JXL=" + state.jxlsave());

        if (!state.available()) {
            throw new IllegalStateException("libvips not available: " + state);
        }
        if (!state.pngsave() || !state.jpegsave() || !state.webpsave() || !state.tiffsave()) {
            throw new IllegalStateException("essential savers missing from bundled libvips");
        }

        byte[] pdf = Base64.getDecoder().decode(PDF_BASE64);
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            System.out.println("Pages: " + doc.pageCount());
            for (VipsFormat format : VipsFormat.values()) {
                if (!VipsAvailability.isFormatAvailable(format)) continue;
                byte[] img = VipsImageConverter.pageToBytes(doc, 0, 150, format);
                if (img == null || img.length < 32) {
                    throw new IllegalStateException("failed to encode page to " + format);
                }
                System.out.println("  [PASS] " + format + " (bytes: " + img.length + ")");
            }
        }

        System.out.println("=== JPDFium verification successful! ===");
    }
}
JAVA

if [ "$MODE" = "staging" ]; then
  export VERIFY_STAGING=true
  export VERIFY_REPO_URL="$REPO_URL"
  export VERIFY_TOKEN="$PORTAL_TOKEN"
fi

gradle run --no-daemon
