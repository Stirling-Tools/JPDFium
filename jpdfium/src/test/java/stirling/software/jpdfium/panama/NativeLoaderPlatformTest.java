package stirling.software.jpdfium.panama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for platform detection (no native load), so they run on every
 * host without the PDFium native present. Guards the linux-musl-* selection used
 * to pick libc-specific natives on Alpine.
 */
class NativeLoaderPlatformTest {

    @Test
    void detectPlatformReturnsKnownKey() {
        String p = NativeLoader.detectPlatform();
        assertTrue(
                p.matches("(linux(-musl)?|darwin|windows)-(x64|arm64)"),
                "unexpected platform key: " + p);
    }

    @Test
    void linuxMuslSuffixMatchesHostLibc() throws Exception {
        String p = NativeLoader.detectPlatform();
        if (!p.startsWith("linux-")) {
            return; // libc distinction only applies to Linux
        }
        // On a musl host (Alpine) the key must carry the -musl- segment; on glibc
        // it must not. Catches accidental removal of the musl branch.
        assertEquals(
                hostHasMuslLoader(),
                p.contains("-musl-"),
                "platform musl suffix should match host libc: " + p);
    }

    private static boolean hostHasMuslLoader() throws Exception {
        Path lib = Path.of("/lib");
        if (!Files.isDirectory(lib)) {
            return false;
        }
        try (DirectoryStream<Path> d = Files.newDirectoryStream(lib, "ld-musl-*")) {
            return d.iterator().hasNext();
        }
    }
}
