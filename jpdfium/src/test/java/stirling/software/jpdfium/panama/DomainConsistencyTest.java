package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the execution-domain migration against regression to scattered
 * locking. Runs without native PDFium: it scans module sources, so new code
 * preserving the old pattern fails the build instead of silently bypassing
 * admission, lifecycle, and shutdown ordering.
 */
class DomainConsistencyTest {

    /**
     * Module sources. Prefers the explicit {@code -Djpdfium.testSrcRoot} root
     * (forwarded to test JVMs by the build convention), else the Gradle test
     * worker directory. Fails loudly when neither resolves: these tripwires
     * must never silently pass on a missing directory.
     */
    private static Path mainSources() {
        String explicit = System.getProperty("jpdfium.testSrcRoot");
        if (explicit != null && !explicit.isBlank()) {
            Path candidate = Path.of(explicit);
            if (!Files.isDirectory(candidate)) {
                throw new IllegalStateException(
                        "jpdfium.testSrcRoot is not a directory: " + explicit);
            }
            return candidate;
        }
        Path candidate = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        if (!Files.isDirectory(candidate)) {
            throw new IllegalStateException(
                    "module sources not found - run via Gradle (which sets jpdfium.testSrcRoot) "
                            + "with a working directory of jpdfium/: " + candidate);
        }
        return candidate;
    }

    private static List<String> grep(Path root, String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains(needle)
                            // The telemetry flag name is user-facing configuration,
                            // not a reference to locking: keep it stable.
                            && !line.contains("jpdfium.nativeGuard.telemetry")) {
                        hits.add(root.relativize(file) + ":" + (i + 1) + ": " + line.strip());
                    }
                }
            }
        }
        return hits;
    }

    @Test
    void noScatteredGuardReferences() throws IOException {
        Path root = mainSources();
        List<String> hits = grep(root, "NativeGuard");
        // The deprecated NativeGuard facade exists only for source/binary
        // compatibility and forwards every call to PdfiumRuntime; it holds no
        // lock of its own, so its own file is not a scattering site.
        hits.removeIf(hit -> hit.contains("panama/NativeGuard.java"));
        assertTrue(hits.isEmpty(),
                () -> "scattered guard management is gone - route through PdfiumRuntime:\n"
                        + String.join("\n", hits));
    }

    @Test
    void noDirectLockUseOutsideDomain() throws IOException {
        Path root = mainSources();
        List<String> hits = new ArrayList<>();
        hits.addAll(grep(root, ".acquire()"));
        hits.addAll(grep(root, "ReentrantLock"));
        // PdfiumRuntime owns the lock. The deprecated NativeGuard facade is
        // allowed too: it delegates to PdfiumRuntime and holds no lock itself.
        hits.removeIf(hit -> hit.contains("panama/PdfiumRuntime.java")
                || hit.contains("panama/NativeGuard.java"));
        // Opt-in slot bounding (QpdfLib/Vips) is admission control, not
        // PDFium serialization. Allowed only inside the slot helpers.
        hits.removeIf(hit -> (hit.contains("panama/QpdfLib.java")
                        && (hit.contains("acquireSlot") || hit.contains("s.acquire()")
                                || hit.contains("permits.acquire()") || hit.contains("s.release()")
                                || hit.contains("permits.release()")))
                || hit.contains("QPDF_PERMITS.acquire()")
                || hit.contains("IMAGE_PERMITS.acquire()"));
        if (!hits.isEmpty()) {
            fail("the domain lock lives only in PdfiumRuntime:\n" + String.join("\n", hits));
        }
    }

    @Test
    void rawDowncallsOnlyViaKnownFactories() throws IOException {
        Path root = mainSources();
        List<String> hits = grep(root, "LINKER.downcallHandle");
        hits.removeIf(hit -> hit.contains("panama/Symbols.java")
                || hit.contains("panama/FastLinks.java")
                || hit.contains("panama/JpdfiumH.java"));
        if (!hits.isEmpty()) {
            fail("raw downcall handles are created only in Symbols/FastLinks/JpdfiumH:\n"
                    + String.join("\n", hits));
        }
    }
}
