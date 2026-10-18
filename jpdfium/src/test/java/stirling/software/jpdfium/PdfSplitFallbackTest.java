package stirling.software.jpdfium;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.doc.ActionType;
import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.PdfBookmarkEditor;
import stirling.software.jpdfium.doc.PdfSecurity;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Split fallbacks: a source qpdf cannot read (password-protected) still splits
 * through files, with the same parts as the heap path and a flat heap.
 */
class PdfSplitFallbackTest {

    private static final String PASSWORD = "user-pass";
    private static final StorageOptions MEMORY = StorageOptions.builder().memory().build();

    private static void assumeProtectedSplit() {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");
        assumeTrue(PdfSecurity.isSupported(), "needs qpdf encryption");
    }

    private static Path protect(Path plain, Path tmp) throws IOException {
        Path enc = tmp.resolve("protected-" + plain.getFileName());
        PdfSecurity.encrypt(plain, enc, PASSWORD, "owner-pass", PdfSecurity.PERM_ALL);
        return enc;
    }

    private static List<byte[]> drain(List<PdfDocument> parts) {
        try {
            List<byte[]> bytes = new ArrayList<>(parts.size());
            for (PdfDocument part : parts) {
                bytes.add(part.saveBytes());
            }
            return bytes;
        } finally {
            for (PdfDocument part : parts) {
                part.close();
            }
        }
    }

    private static byte[] drain(PdfDocument part) {
        try (part) {
            return part.saveBytes();
        }
    }

    private static void assertSameParts(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size(), "part count differs from the heap path");
        for (int p = 0; p < expected.size(); p++) {
            int pages = PdfVerifier.pageCount(expected.get(p), "heap part " + p);
            assertEquals(pages, PdfVerifier.pageCount(actual.get(p), "file part " + p));
            for (int i = 0; i < pages; i++) {
                assertEquals(PdfVerifier.pageText(expected.get(p), i, "heap part " + p),
                        PdfVerifier.pageText(actual.get(p), i, "file part " + p),
                        "part " + p + " page " + i + " text differs between storage modes");
            }
        }
    }

    @Test
    void protectedSourceSplitMatchesMemoryMode(@TempDir Path tmp) throws Exception {
        assumeProtectedSplit();
        Path plain = tmp.resolve("plain.pdf");
        Files.write(plain, SyntheticPdfFactory.createDiverse(6));
        Path enc = protect(plain, tmp);

        try (PdfDocument doc = PdfDocument.open(enc, PASSWORD)) {
            List<byte[]> memory = drain(PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(2), MEMORY));
            List<byte[]> auto = drain(PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(2)));
            assertEquals(3, auto.size());
            assertSameParts(memory, auto);
            for (int i = 0; i < 2; i++) {
                PdfVerifier.assertContainsText(auto.get(1), i, "Synthetic corpus page " + (3 + i),
                        "middle part");
            }
        }
    }

    @Test
    void protectedSourceExtractMatchesMemoryMode(@TempDir Path tmp) throws Exception {
        assumeProtectedSplit();
        Path plain = tmp.resolve("plain.pdf");
        Files.write(plain, SyntheticPdfFactory.createDiverse(6));
        Path enc = protect(plain, tmp);
        Set<Integer> sparse = new TreeSet<>(List.of(0, 3, 5));

        try (PdfDocument doc = PdfDocument.open(enc, PASSWORD)) {
            assertSameParts(List.of(drain(PdfSplit.extractPageRange(doc, 1, 4, MEMORY))),
                    List.of(drain(PdfSplit.extractPageRange(doc, 1, 4))));
            byte[] picked = drain(PdfSplit.extractPages(doc, sparse));
            assertSameParts(List.of(drain(PdfSplit.extractPages(doc, sparse, MEMORY))),
                    List.of(picked));
            PdfVerifier.assertContainsText(picked, 1, "Synthetic corpus page 4", "sparse extract");
        }
    }

    @Test
    void protectedSourceKeepsRemappedBookmarks(@TempDir Path tmp) throws Exception {
        assumeProtectedSplit();
        Path plain = tmp.resolve("plain.pdf");
        Files.write(plain, SyntheticPdfFactory.createDiverse(6));
        Path marked = tmp.resolve("marked.pdf");
        try (PdfDocument doc = PdfDocument.open(plain)) {
            PdfBookmarkEditor.setBookmarks(doc, List.of(goTo("First", 0), goTo("Fourth", 3)), marked);
        }
        Path enc = protect(marked, tmp);

        try (PdfDocument doc = PdfDocument.open(enc, PASSWORD)) {
            List<PdfDocument> parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(3));
            try {
                assertEquals(2, parts.size());
                assertEquals("First", parts.get(0).bookmarks().getFirst().title());
                Bookmark second = parts.get(1).bookmarks().getFirst();
                assertEquals("Fourth", second.title());
                assertEquals(0, second.pageIndex(), "bookmark must be remapped into the part");
            } finally {
                for (PdfDocument part : parts) part.close();
            }
        }
    }

    private static Bookmark goTo(String title, int page) {
        return new Bookmark(title, page, List.of(), ActionType.GOTO, Optional.empty(), Optional.empty());
    }

    @Test
    void fileModeFailsLoudlyWhenQpdfRejectsTheSource(@TempDir Path tmp) throws Exception {
        assumeProtectedSplit();
        Path plain = tmp.resolve("plain.pdf");
        Files.write(plain, SyntheticPdfFactory.createDiverse(4));
        Path enc = protect(plain, tmp);
        StorageOptions file = StorageOptions.builder().file().build();

        try (PdfDocument doc = PdfDocument.open(enc, PASSWORD)) {
            JPDFiumException split = assertThrows(JPDFiumException.class,
                    () -> PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(2), file));
            assertTrue(split.getMessage().contains("file-backed"), split.getMessage());
            JPDFiumException range = assertThrows(JPDFiumException.class,
                    () -> PdfSplit.extractPageRange(doc, 0, 1, file));
            assertTrue(range.getMessage().contains("file-backed"), range.getMessage());
        }
    }

    @Test
    @Timeout(300)
    void protectedSourceSplitStaysFlatInHeap(@TempDir Path tmp) throws Exception {
        assumeProtectedSplit();
        Path plain = tmp.resolve("big.pdf");
        writeFillerPdf(plain, 16, 1_000_000);
        Path enc = protect(plain, tmp);
        long sourceSize = Files.size(enc);

        ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        assumeTrue(tmx.isThreadAllocatedMemorySupported() && tmx.isThreadAllocatedMemoryEnabled(),
                "thread allocation measurement is unavailable");
        try (PdfDocument doc = PdfDocument.open(enc, PASSWORD)) {
            // Warmup pays one-time native init (symbol lookup, first segments).
            drain(PdfSplit.extractPageRange(doc, 0, 0));
            System.gc();
            long before = tmx.getThreadAllocatedBytes(tid);
            List<PdfDocument> parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(4));
            long after = tmx.getThreadAllocatedBytes(tid);
            long allocated = after - before;
            assertTrue(before >= 0 && after >= 0, "thread allocation measurement returned -1");
            int total = 0;
            try {
                for (PdfDocument part : parts) {
                    total += part.pageCount();
                }
            } finally {
                for (PdfDocument part : parts) {
                    part.close();
                }
            }
            assertEquals(16, total);
            System.out.printf("MEM split(protected) %.1f MB source, 4 parts -> %.1f KB Java heap%n",
                    sourceSize / 1e6, allocated / 1024.0);
            assertTrue(allocated < sourceSize / 4,
                    "protected split allocated " + allocated + " B for a " + sourceSize
                            + " B source - the source was materialized on the heap");

            before = tmx.getThreadAllocatedBytes(tid);
            try (PdfDocument range = PdfSplit.extractPageRange(doc, 4, 11)) {
                long afterRange = tmx.getThreadAllocatedBytes(tid);
                allocated = afterRange - before;
                assertTrue(before >= 0 && afterRange >= 0, "thread allocation measurement returned -1");
                assertEquals(8, range.pageCount());
            }
            System.out.printf("MEM extractPageRange(protected) %.1f MB source -> %.1f KB Java heap%n",
                    sourceSize / 1e6, allocated / 1024.0);
            assertTrue(allocated < sourceSize / 4,
                    "protected range extract allocated " + allocated + " B for a " + sourceSize
                            + " B source - the source was materialized on the heap");
        }
    }

    /**
     * Streams a PDF with incompressible filler to disk, so neither qpdf's
     * recompression nor the fixture itself hides a whole-document copy.
     */
    static void writeFillerPdf(Path out, int pages, int fillerBytes) throws IOException {
        byte[] filler = new byte[fillerBytes];
        new Random(42).nextBytes(filler);
        long[] offsets = new long[3 + 2 * pages];
        try (CountingOutput os = new CountingOutput(Files.newOutputStream(out))) {
            os.ascii("%PDF-1.4\n");
            offsets[1] = os.count;
            os.ascii("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n");
            offsets[2] = os.count;
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < pages; i++) kids.append(3 + i).append(" 0 R ");
            os.ascii("2 0 obj<</Type/Pages/Kids[" + kids.toString().trim() + "]/Count " + pages
                    + ">>endobj\n");
            int firstContent = 3 + pages;
            for (int i = 0; i < pages; i++) {
                offsets[3 + i] = os.count;
                os.ascii((3 + i) + " 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents "
                        + (firstContent + i) + " 0 R>>endobj\n");
            }
            for (int i = 0; i < pages; i++) {
                offsets[firstContent + i] = os.count;
                os.ascii((firstContent + i) + " 0 obj<</Length " + filler.length + ">>\nstream\n");
                os.write(filler);
                os.ascii("\nendstream\nendobj\n");
            }
            long xref = os.count;
            os.ascii("xref\n0 " + offsets.length + "\n0000000000 65535 f \n");
            for (int i = 1; i < offsets.length; i++) {
                os.ascii(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
            }
            os.ascii("trailer<</Size " + offsets.length + "/Root 1 0 R>>\nstartxref\n" + xref
                    + "\n%%EOF");
        }
    }

    private static final class CountingOutput extends BufferedOutputStream {
        long count;

        CountingOutput(OutputStream out) {
            super(out, 1 << 16);
        }

        void ascii(String s) throws IOException {
            write(s.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            count += len;
        }
    }
}
