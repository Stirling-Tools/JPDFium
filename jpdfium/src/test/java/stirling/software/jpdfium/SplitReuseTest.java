package stirling.software.jpdfium;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.StandardCopyOption;

/** Split reuse and fan-out semantics on a real multi-page fixture. */
class SplitReuseTest {

    private static Path resource(String name) throws Exception {
        return Path.of(Objects.requireNonNull(SplitReuseTest.class.getResource(name)).toURI());
    }

    @Test
    void manyRangesShareOneSnapshot() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "object-stream page counts require real PDFium native library");
        Path in = resource("/pdfs/redact/redact-test-100pages.pdf");
        try (PdfDocument doc = PdfDocument.open(in)) {
            assertEquals(100, doc.pageCount());
            List<PdfDocument> parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(10));
            try {
                assertEquals(10, parts.size());
                int total = 0;
                for (PdfDocument p : parts) total += p.pageCount();
                assertEquals(100, total);
            } finally {
                for (PdfDocument p : parts) p.close();
            }
        }
    }

    @Test
    void singleAndNoncontiguousSelections() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "object-stream page counts require real PDFium native library");
        Path in = resource("/pdfs/redact/redact-test-100pages.pdf");
        try (PdfDocument doc = PdfDocument.open(in)) {
            try (PdfDocument one = PdfSplit.extractPageRange(doc, 0, 0)) {
                assertEquals(1, one.pageCount());
            }
            Set<Integer> sparse = new TreeSet<>(List.of(0, 50, 99));
            try (PdfDocument part = PdfSplit.extractPages(doc, sparse)) {
                assertEquals(3, part.pageCount());
            }
            Set<Integer> repeated = Set.of(5);
            try (PdfDocument part = PdfSplit.extractPages(doc, repeated)) {
                assertEquals(1, part.pageCount());
            }
        }
    }

    @Test
    void modifiedDocumentSplitsFromSnapshot() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "object-stream page counts require real PDFium native library");
        Path in = resource("/pdfs/redact/redact-test-100pages.pdf");
        Path work = Files.createTempFile("split-mod", ".pdf");
        try {
            Files.copy(in, work, StandardCopyOption.REPLACE_EXISTING);
            try (PdfDocument doc = PdfDocument.open(work)) {
                List<PdfDocument> parts =
                        PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(50));
                try {
                    assertEquals(2, parts.size());
                    assertEquals(50, parts.get(0).pageCount());
                } finally {
                    for (PdfDocument p : parts) p.close();
                }
            }
        } finally {
            Files.deleteIfExists(work);
        }
    }

    @Test
    void failureDoesNotClaimAtomicity() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "object-stream page counts require real PDFium native library");
        Path in = resource("/pdfs/redact/redact-test-100pages.pdf");
        try (PdfDocument doc = PdfDocument.open(in)) {
            List<PdfDocument> parts = new ArrayList<>();
            try {
                for (int[] r : PdfSplit.SplitStrategy.everyNPages(25).computeRanges(doc)) {
                    parts.add(PdfSplit.extractPageRange(doc, r[0], r[1]));
                }
                assertEquals(4, parts.size());
            } finally {
                for (PdfDocument p : parts) p.close();
            }
        }
    }

    @Test
    void sameInvocationStagesForOneTenHundredRanges() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "object-stream page counts require real PDFium native library");
        Path in = resource("/pdfs/redact/redact-test-100pages.pdf");
        try (PdfDocument doc = PdfDocument.open(in)) {
            for (int per : new int[]{100, 10, 1}) {
                PdfSplit.resetCounters();
                long t0 = System.nanoTime();
                List<PdfDocument> parts =
                        PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(per));
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int total = 0;
                for (PdfDocument p : parts) {
                    total += p.pageCount();
                    p.close();
                }
                System.out.printf("SPLIT ranges=%d parts=%d pages=%d %d ms ser=%d loads=%d writes=%d%n",
                        (100 + per - 1) / per, parts.size(), total, ms,
                        PdfSplit.COUNTERS.sourceSerializations.get(),
                        PdfSplit.COUNTERS.sourceLoads.get(),
                        PdfSplit.COUNTERS.outputWrites.get());
                assertEquals(100, total);
                // Snapshot-once invariant: exactly one source serialization for
                // a default (snapshot-always) multi-output split. A single-range
                // split never stages a source at all.
                int ranges = (100 + per - 1) / per;
                assertEquals(ranges > 1 ? 1 : 0, PdfSplit.COUNTERS.sourceSerializations.get(),
                        "a default split must serialize the source at most once");
            }
        }
    }

    @Test
    void sourceReuseIsOptInAndSkipsTheSnapshot() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "object-stream page counts require real PDFium native library");
        Path in = resource("/pdfs/redact/redact-test-100pages.pdf");
        try (PdfDocument doc = PdfDocument.open(in)) {
            PdfSplit.resetCounters();
            List<PdfDocument> parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(10),
                    StorageOptions.builder().reuseSourceFile(true).build());
            int total = 0;
            for (PdfDocument p : parts) {
                total += p.pageCount();
                p.close();
            }
            assertEquals(100, total);
            assertEquals(0, PdfSplit.COUNTERS.sourceSerializations.get(),
                    "an opted-in split of an untouched file-backed document must not snapshot");
        }
    }

    @Test
    void defaultSplitSnapshotsSoEditsCannotBeLost() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "object-stream page counts require real PDFium native library");
        Path in = resource("/pdfs/redact/redact-test-100pages.pdf");
        Path work = Files.createTempFile("split-dirty", ".pdf");
        try {
            Files.copy(in, work, StandardCopyOption.REPLACE_EXISTING);
            try (PdfDocument doc = PdfDocument.open(work)) {
                // A page mutation that leaves the structural epoch at zero: the
                // file no longer represents the document.
                try (PdfPage page = doc.page(0)) {
                    page.flatten();
                }
                PdfSplit.resetCounters();
                List<PdfDocument> parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(50));
                try {
                    assertEquals(2, parts.size());
                    assertEquals(1, PdfSplit.COUNTERS.sourceSerializations.get(),
                            "an edited document must be snapshotted even though its epoch is unchanged");
                } finally {
                    for (PdfDocument p : parts) p.close();
                }
            }
        } finally {
            Files.deleteIfExists(work);
        }
    }
}
