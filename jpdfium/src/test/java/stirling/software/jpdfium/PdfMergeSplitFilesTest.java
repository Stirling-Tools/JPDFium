package stirling.software.jpdfium;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * File-backed merge/split: same results as the bytes paths, flat heap.
 */
class PdfMergeSplitFilesTest {

    @Test
    void mergeFilesToFileMatchesBytesPath(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");

        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Path c = tmp.resolve("c.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));
        Files.write(c, SyntheticPdfFactory.createDiverse(4));

        Path out = tmp.resolve("merged.pdf");
        PdfMerge.mergeFilesToFile(List.of(a, b, c), out);
        assertTrue(Files.size(out) > 0, "merged file must be non-empty");

        byte[] fileBytes = Files.readAllBytes(out);
        assertEquals(9, PdfVerifier.pageCount(fileBytes, "file-merged doc"));
        try (PdfDocument check = PdfDocument.open(fileBytes)) {
            assertEquals(9, check.pageCount());
        }

        // Same content as the bytes path: page text must agree on every page.
        byte[] bytesPath;
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(a, b, c))) {
            bytesPath = merged.saveBytes();
        }
        assertEquals(9, PdfVerifier.pageCount(bytesPath, "bytes-merged doc"));
        for (int i = 0; i < 9; i++) {
            assertEquals(PdfVerifier.pageText(bytesPath, i, "bytes path"),
                    PdfVerifier.pageText(fileBytes, i, "file path"),
                    "page " + i + " text differs between merge paths");
        }
    }

    @Test
    void extractPageRangeToFileMatchesBytesPath(@TempDir Path tmp) throws Exception {        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");

        Path in = tmp.resolve("in.pdf");
        Files.write(in, SyntheticPdfFactory.createDiverse(6));

        Path out = tmp.resolve("part.pdf");
        PdfSplit.extractPageRangeToFile(in, 1, 4, out);
        byte[] fileBytes = Files.readAllBytes(out);
        assertEquals(4, PdfVerifier.pageCount(fileBytes, "file-extracted doc"));

        byte[] bytesPath;
        try (PdfDocument doc = PdfDocument.open(in);
             PdfDocument part = PdfSplit.extractPageRange(doc, 1, 4)) {
            bytesPath = part.saveBytes();
        }
        for (int i = 0; i < 4; i++) {
            assertEquals(PdfVerifier.pageText(bytesPath, i, "bytes path"),
                    PdfVerifier.pageText(fileBytes, i, "file path"),
                    "page " + i + " text differs between split paths");
        }
    }

    @Test
    void mergeOpenFileBackedDocsMatchesBytesPath(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");

        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));

        byte[] bytesPath;
        Path backing;
        try (PdfDocument doc1 = PdfDocument.open(a);
             PdfDocument doc2 = PdfDocument.open(b);
             PdfDocument merged = PdfMerge.merge(List.of(doc1, doc2))) {
            assertEquals(5, merged.pageCount());
            // Temp-backed result cleans itself up on close.
            assertTrue(merged.sourcePath().isPresent(), "merged doc tracks its backing file");
            backing = merged.sourcePath().get();
            bytesPath = merged.saveBytes();
        }
        assertTrue(java.nio.file.Files.notExists(backing), "backing temp file deleted on close");

        assertEquals(5, PdfVerifier.pageCount(bytesPath, "merged open docs"));
        for (int i = 0; i < 5; i++) {
            PdfVerifier.assertNonEmptyText(bytesPath, i, "merged open docs page " + i);
        }
    }

    @Test
    void extractRangeFromFileBackedDocMatchesBytesPath(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");

        Path in = tmp.resolve("in.pdf");
        Files.write(in, SyntheticPdfFactory.createDiverse(6));

        byte[] filePath;
        try (PdfDocument doc = PdfDocument.open(in);
             PdfDocument part = PdfSplit.extractPageRange(doc, 1, 4)) {
            assertEquals(4, part.pageCount());
            filePath = part.saveBytes();
        }
        byte[] bytesPath;
        try (PdfDocument doc = PdfDocument.open(Files.readAllBytes(in));
             PdfDocument part = PdfSplit.extractPageRange(doc, 1, 4)) {
            bytesPath = part.saveBytes();
        }
        assertEquals(4, PdfVerifier.pageCount(filePath, "file-backed extract"));
        for (int i = 0; i < 4; i++) {
            assertEquals(PdfVerifier.pageText(bytesPath, i, "bytes path"),
                    PdfVerifier.pageText(filePath, i, "file path"),
                    "page " + i + " text differs between split paths");
        }
    }

    @Test
    void fileMergeStaysFlatInHeap(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");

        // 8 copies of a 5-page doc: big enough that any byte[] round-trip
        // shows up clearly in thread allocations.
        Path src = tmp.resolve("src.pdf");
        Files.write(src, SyntheticPdfFactory.createDiverse(5));
        long srcSize = Files.size(src);
        List<Path> inputs = java.util.Collections.nCopies(8, src);

        ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        // Warmup pays one-time native init (symbol lookup, first segments).
        Path warm = tmp.resolve("warm.pdf");
        PdfMerge.mergeFilesToFile(inputs.subList(0, 2), warm);
        System.gc();
        long before = tmx.getThreadAllocatedBytes(tid);
        Path out = tmp.resolve("merged.pdf");
        PdfMerge.mergeFilesToFile(inputs, out);
        long allocated = tmx.getThreadAllocatedBytes(tid) - before;

        long outSize = Files.size(out);
        assertTrue(outSize > srcSize, "merged output must exceed one input");
        // File-backed merge must not allocate anywhere near the output size.
        assertTrue(allocated < outSize,
                "file merge allocated " + allocated + " bytes for " + outSize
                        + " bytes of output - document bytes leaked onto the heap");
    }
}
