package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.NativeLoadException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLoaderTest {

    private static final Pattern PATTERN = Pattern.compile("(linux(-musl)?|darwin|windows)-(x64|arm64)");

    @Test
    void detectsPlatformCorrectly() {
        String platform = NativeLoader.detectPlatform();
        assertTrue(
                PATTERN.matcher(platform).matches(),
                "Unexpected platform string: " + platform);
    }

    @Test
    void loadsNativeLibrary() {
        assertDoesNotThrow(NativeLoader::ensureLoaded);
    }

    @Test
    void idempotentLoad() {
        // Calling twice must not throw
        NativeLoader.ensureLoaded();
        assertDoesNotThrow(NativeLoader::ensureLoaded);
    }

    /**
     * Production loading must fail closed: a natives jar that lists libraries
     * without an integrity manifest is never extracted and loaded. This pins
     * the behaviour so a future change cannot quietly downgrade it - and is why
     * test tasks must NOT set {@code jpdfium.natives.allowUnsigned} globally.
     */
    @Test
    void refusesUnsignedNativesManifestByDefault() {
        assertFalse(Boolean.getBoolean("jpdfium.natives.allowUnsigned"),
                "tests must not globally disable the unsigned-natives guard");

        NativeLoadException ex =
                assertThrows(NativeLoadException.class,
                        () -> NativeLoader.failClosedOnMissingChecksums("linux-x64"));
        assertTrue(rootCauseMessage(ex).contains("refusing to load unverified native libraries"),
                "fail-closed must name the reason: " + rootCauseMessage(ex));
    }

    /** The explicit opt-in still bypasses the guard, for local development. */
    @Test
    void unsignedOptInIsHonouredWhenSetExplicitly() {
        String previous = System.getProperty("jpdfium.natives.allowUnsigned");
        System.setProperty("jpdfium.natives.allowUnsigned", "true");
        try {
            NativeLoader.failClosedOnMissingChecksums("linux-x64");
        } finally {
            if (previous == null) {
                System.clearProperty("jpdfium.natives.allowUnsigned");
            } else {
                System.setProperty("jpdfium.natives.allowUnsigned", previous);
            }
        }
    }

    /** Every packaged library must be covered by the checksum manifest. */
    @Test
    void everyPackagedLibraryIsChecksummed() throws IOException {
        String platform = NativeLoader.detectPlatform();
        String base = "/natives/" + platform + "/";
        try (InputStream index = NativeLoader.class.getResourceAsStream(base + "native-libs.txt")) {
            List<String> libs = index == null ? List.of() : readLines(index);
            if (libs.isEmpty()) {
                return;  // stub/fallback classpath: nothing to attest
            }
            String manifest;
            try (InputStream sums = NativeLoader.class.getResourceAsStream(
                    base + "native-libs.sha256")) {
                manifest = sums == null ? "" : readText(sums);
            }
            Map<String, String> checksums = NativeCache.parseChecksums(manifest);
            assertFalse(checksums.isEmpty(), "natives jar ships libraries without checksums");
            for (String lib : libs) {
                assertTrue(checksums.containsKey(lib), "library not in checksum manifest: " + lib);
                assertNotNull(NativeLoader.class.getResource(base + lib), "missing: " + lib);
            }
        }
    }

    private static List<String> readLines(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            in.transferTo(bos);
            return java.util.Arrays.stream(bos.toString(StandardCharsets.UTF_8).split("\\R"))
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && l.charAt(0) != '#')
                    .toList();
        }
    }

    private static String readText(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            in.transferTo(bos);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String rootCauseMessage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null) return c.getMessage();
        }
        return "";
    }
}
