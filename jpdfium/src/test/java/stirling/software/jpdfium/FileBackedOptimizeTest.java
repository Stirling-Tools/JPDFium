package stirling.software.jpdfium;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import stirling.software.jpdfium.doc.PdfOptimizer;
import stirling.software.jpdfium.panama.QpdfLib;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;

/**
 * The file-backed optimize route must keep the document out of Java heap.
 *
 * <p>The byte[] route necessarily holds the input and the output at once, so
 * peak heap scales with document size. This asserts the file route does not,
 * which is the whole point of adding it.
 */
class FileBackedOptimizeTest {

    private static final ThreadMXBean BEAN =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long TID = Thread.currentThread().threadId();

    private static Path resource(String name) throws Exception {
        return Path.of(Objects.requireNonNull(
                FileBackedOptimizeTest.class.getResource(name)).toURI());
    }

    private static double bytesPerOp(Runnable op, int warmup, int iterations) {
        for (int i = 0; i < warmup; i++) {
            op.run();
        }
        long before = BEAN.getThreadAllocatedBytes(TID);
        for (int i = 0; i < iterations; i++) {
            op.run();
        }
        long after = BEAN.getThreadAllocatedBytes(TID);
        return (after - before) / (double) iterations;
    }

    @Test
    void fileRouteIsAvailableInThisBuild() {
        assertTrue(QpdfLib.isOptimizeFileSupported(),
                "file-backed optimize downcall must resolve in a qpdf build");
    }

    @Test
    @Timeout(300)
    void fileOptimizeProducesAValidPdf() throws Exception {
        Path in = resource("/pdfs/general/minimal.pdf");
        Path out = Files.createTempFile("opt-out", ".pdf");
        try {
            Files.deleteIfExists(out);
            PdfOptimizer.optimize(in, out, 0, 0, 1, 1, 0);
            assertTrue(Files.size(out) > 0, "optimize must produce a non-empty file");
            byte[] head = new byte[5];
            try (var in2 = Files.newInputStream(out)) {
                assertEquals(5, in2.read(head));
            }
            assertEquals("%PDF-", new String(head, StandardCharsets.ISO_8859_1),
                    "output must be a PDF");
            try (PdfDocument doc = PdfDocument.open(out)) {
                assertTrue(doc.pageCount() > 0, "optimized output must still open");
            }
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    @Timeout(600)
    void fileOptimizeDoesNotMaterializeTheDocument() throws Exception {
        Path resolved = resource("/pdfs/redact/redact-test-tj-deviation.pdf");
        final Path in = Files.exists(resolved)
                ? resolved
                : resource("/pdfs/general/minimal.pdf");
        final long fileSize = Files.size(in);
        final Path out = Files.createTempFile("opt-alloc", ".pdf");
        try {
            Files.deleteIfExists(out);
            double perOp = bytesPerOp(() -> {
                try {
                    PdfOptimizer.optimize(in, out, 0, 0, 1, 1, 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 2, 6);

            System.out.printf("MEM optimize file->file %.1f MB input -> %.1f KB/op Java heap%n",
                    fileSize / 1e6, perOp / 1024);
            assertTrue(perOp < fileSize / 4,
                    "file-backed optimize allocated " + perOp + " B/op for a " + fileSize
                            + " B document - the document is being materialized");
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    @Timeout(120)
    void unreadableInputIsRefusedWithoutPublishingOutput() throws Exception {
        Path out = Files.createTempFile("opt-fail", ".pdf");
        try {
            long before = Files.size(out);
            Path missing = out.resolveSibling("does-not-exist-input.pdf");
            // An unreadable input is a capability-style refusal (false), the
            // same contract mergeFiles uses - not an exception. What matters is
            // that the destination is left untouched either way.
            boolean result = QpdfLib.optimizeFile(missing, out, 0, 1, 1, 0);
            assertTrue(!result, "an unreadable input must not report success");
            assertEquals(before, Files.size(out),
                    "a refused run must not publish anything over the destination");
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
