#!/usr/bin/env bash
# Post-release check: pulls the published JPDFium artifacts from Maven Central
# into a throwaway Gradle project and runs a full smoke (natives + libvips).
# Called by the validate-maven-central job in release.yml with VERSION and
# PLATFORM provided via the environment.
set -euo pipefail
echo "Verifying JPDFium $VERSION ($PLATFORM) from Maven Central..."

# Poll Maven Central with backoff up to 25 minutes
CENTRAL_URL="https://repo1.maven.org/maven2/com/stirling/jpdfium-natives-vips-${PLATFORM}/${VERSION}/jpdfium-natives-vips-${PLATFORM}-${VERSION}.jar"
echo "Polling $CENTRAL_URL..."

MAX_ATTEMPTS=50
ATTEMPT=0
FOUND=0
while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
  ATTEMPT=$((ATTEMPT + 1))
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$CENTRAL_URL" || true)
  if [ "$STATUS" = "200" ]; then
    echo "Found published artifact on Maven Central (attempt $ATTEMPT)!"
    FOUND=1
    break
  fi
  echo "Attempt $ATTEMPT: artifact not yet visible (HTTP $STATUS), waiting 30s..."
  sleep 30
done

if [ $FOUND -ne 1 ]; then
  echo "ERROR: Artifact $CENTRAL_URL did not become available on Maven Central within timeout."
  exit 1
fi

# Create a standalone Gradle validation project that pulls solely from Maven Central
WORK_DIR=$(mktemp -d 2>/dev/null || mktemp -d -t 'jpdfium-verify')
cd "$WORK_DIR"

cat << 'BUILD' > settings.gradle.kts
rootProject.name = "jpdfium-central-verifier"
BUILD

cat << BUILD > build.gradle.kts
plugins {
    `java-application`
}

repositories {
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
}
BUILD

mkdir -p src/main/java
cat << 'JAVA' > src/main/java/VerifyCentral.java
import java.io.*;
import java.nio.file.*;
import java.util.*;
import stirling.software.jpdfium.*;
import stirling.software.jpdfium.vips.*;

public class VerifyCentral {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Verifying Central Published JPDFium + Libvips Natives ===");

        // 1. Initialize natives
        VipsNatives.configure();
        NativeLoader.ensureLoaded();

        // 2. Probe libvips availability and all format savers
        VipsAvailability.State state = VipsAvailability.probe();
        System.out.println("Libvips available: " + state.available());
        System.out.println("Platform: " + state.platform());
        System.out.println("Version: " + state.version());
        System.out.println("Savers: PNG=" + state.pngsave() + ", JPEG=" + state.jpegsave() + ", WEBP=" + state.webpsave()
                + ", TIFF=" + state.tiffsave() + ", HEIF=" + state.heifsave() + ", JXL=" + state.jxlsave());

        if (!state.available()) {
            throw new IllegalStateException("Libvips not available: " + VipsAvailability.installMessage(state));
        }
        if (!state.pngsave() || !state.jpegsave() || !state.webpsave() || !state.tiffsave()) {
            throw new IllegalStateException("Essential savers missing from bundled libvips!");
        }

        // 3. Create a blank PDF via PdfDocument, render page to PNG, JPEG, WEBP, TIFF, HEIF, JXL
        try (PdfDocument doc = PdfDocument.blank(612, 792)) {
            System.out.println("Testing PDF page conversion to multiple image formats...");
            for (VipsFormat format : VipsFormat.values()) {
                byte[] img = VipsImageConverter.pageToBytes(doc, 0, 150, format);
                if (img == null || img.length < 32) {
                    throw new IllegalStateException("Failed to encode page to " + format);
                }
                System.out.println("  [PASS] " + format + " (bytes: " + img.length + ")");
            }
        }

        System.out.println("=== Maven Central JPDFium verification successful! ===");
    }
}
JAVA

# Run the standalone verification using gradle
gradle run --no-daemon --jvm-args="--enable-native-access=ALL-UNNAMED"
