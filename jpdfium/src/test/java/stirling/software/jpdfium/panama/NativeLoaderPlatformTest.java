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
 * to pick libc-specific natives on Alpine / musl runtimes.
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
        // On a musl host (Alpine) the key must carry the -musl- segment; on
        // glibc it must not. Catches accidental removal of the musl branch.
        assertEquals(
                hostHasMuslLoader(),
                p.contains("-musl-"),
                "platform musl suffix should match host libc: " + p);
    }

    private static boolean hostHasMuslLoader() throws Exception {
        for (String libDir : new String[] {"/lib", "/usr/lib"}) {
            Path lib = Path.of(libDir);
            if (!Files.isDirectory(lib)) continue;
            try (DirectoryStream<Path> d = Files.newDirectoryStream(lib, "ld-musl-*")) {
                if (d.iterator().hasNext()) return true;
            }
        }
        return false;
    }

    @Test
    void windowsPreloadOrderLoadsIcuBeforeHarfbuzzNg() {
        // windows-arm64 died with 0xC0000139 when harfbuzz-ng loaded
        // before the bundled icuuc.dll.
        assertTrue(
                NativeLoader.windowsLoadTier("icuuc.dll")
                        < NativeLoader.windowsLoadTier("third_party_harfbuzz-ng.dll"),
                "icuuc.dll must load before third_party_harfbuzz-ng.dll");
        assertTrue(
                NativeLoader.windowsLoadTier("libc++.dll")
                        < NativeLoader.windowsLoadTier("icuuc.dll"),
                "libc++.dll must load before icuuc.dll");
        assertTrue(
                NativeLoader.windowsLoadTier("freetype.dll")
                        < NativeLoader.windowsLoadTier("harfbuzz.dll"),
                "freetype.dll must load before harfbuzz.dll");
        assertTrue(
                NativeLoader.windowsLoadTier("harfbuzz.dll")
                        < NativeLoader.windowsLoadTier("harfbuzz-subset.dll"),
                "harfbuzz.dll must load before harfbuzz-subset.dll");
        assertTrue(
                NativeLoader.windowsLoadTier("icudt78.dll")
                        < NativeLoader.windowsLoadTier("icuuc78.dll"),
                "icudt78.dll must load before icuuc78.dll");
    }
}
