package stirling.software.jpdfium;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        Path mergeTmp = tmp.resolve("merge-tmp");
        byte[] bytesPath;
        try (PdfDocument doc1 = PdfDocument.open(a);
             PdfDocument doc2 = PdfDocument.open(b);
             PdfDocument merged = PdfMerge.merge(List.of(doc1, doc2),
                     StorageOptions.builder().tempDir(mergeTmp).build())) {
            assertEquals(5, merged.pageCount());
            bytesPath = merged.saveBytes();
        }
        try (var leftovers = Files.list(mergeTmp)) {
            assertEquals(0, leftovers.count(), "merge temp files must be cleaned up");
        }

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
    void memoryModeMergesWithoutFilePath(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));

        StorageOptions memory = StorageOptions.builder().memory().build();
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(a, b), memory)) {
            assertEquals(5, merged.pageCount());
        }
        Path out = tmp.resolve("out.pdf");
        PdfSplit.extractPageRangeToFile(a, 0, 1, out, memory);
        assertEquals(2, PdfVerifier.pageCount(Files.readAllBytes(out), "memory split"));
    }

    @Test
    void fileModeWorksForBytesOpenedDocs(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported() && QpdfLib.isExtractFileSupported(),
                "needs file-backed qpdf symbols");
        byte[] pdf = SyntheticPdfFactory.createDiverse(2);
        StorageOptions file = StorageOptions.builder().file().tempDir(tmp.resolve("t")).build();
        try (PdfDocument doc = PdfDocument.open(pdf);
             PdfDocument part = PdfSplit.extractPageRange(doc, 0, 1, file)) {
            assertEquals(2, part.pageCount());
        }
        try (PdfDocument doc = PdfDocument.open(pdf);
             PdfDocument other = PdfDocument.open(pdf);
             PdfDocument merged = PdfMerge.merge(List.of(doc, other), file)) {
            assertEquals(4, merged.pageCount());
        }
    }

    @Test
    void fileBackedOpsReflectInMemoryEdits(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported() && QpdfLib.isExtractFileSupported(),
                "needs file-backed qpdf symbols");
        Path in = tmp.resolve("in.pdf");
        byte[] source = SyntheticPdfFactory.createDiverse(3);
        Files.write(in, source);
        String expectedPage1 = PdfVerifier.pageText(source, 1, "source page 1");
        StorageOptions file = StorageOptions.builder().file().tempDir(tmp.resolve("t")).build();

        // Page 0 deleted in memory: the extract must not read the stale file.
        try (PdfDocument doc = PdfDocument.open(in)) {
            PdfPageEditor.deletePage(doc.rawHandle(), 0);
            try (PdfDocument part = PdfSplit.extractPageRange(doc, 0, 0, file)) {
                assertEquals(1, part.pageCount());
                assertEquals(expectedPage1,
                        PdfVerifier.pageText(part.saveBytes(), 0, "edited extract"));
            }
        }

        try (PdfDocument doc = PdfDocument.open(in);
             PdfDocument other = PdfDocument.open(SyntheticPdfFactory.createDiverse(1))) {
            PdfPageEditor.deletePage(doc.rawHandle(), 0);
            try (PdfDocument merged = PdfMerge.merge(List.of(doc, other), file)) {
                assertEquals(3, merged.pageCount());
                assertEquals(expectedPage1,
                        PdfVerifier.pageText(merged.saveBytes(), 0, "edited merge"));
            }
        }
    }

    @Test
    void extractRangeToFileRejectsOutOfRangePages(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("in.pdf");
        Files.write(in, SyntheticPdfFactory.createDiverse(6));
        Path out = tmp.resolve("part.pdf");
        assertThrows(IllegalArgumentException.class,
                () -> PdfSplit.extractPageRangeToFile(in, 1, 100, out));
        assertThrows(IllegalArgumentException.class,
                () -> PdfSplit.extractPageRangeToFile(in, 7, 8, out));
        assertThrows(IllegalArgumentException.class,
                () -> PdfSplit.extractPageRangeToFile(in, 4, 2, out));
    }

    @Test
    void fileModeMergesFileBackedDocs(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));

        Path customTmp = tmp.resolve("custom-tmp");
        StorageOptions file = StorageOptions.builder().file().tempDir(customTmp).build();
        try (PdfDocument doc1 = PdfDocument.open(a);
             PdfDocument doc2 = PdfDocument.open(b);
             PdfDocument merged = PdfMerge.merge(List.of(doc1, doc2), file)) {
            assertEquals(5, merged.pageCount());
        }
        assertTrue(Files.isDirectory(customTmp), "custom temp dir is used");
    }

    @Test
    void mergeFilesToFileCanReplaceAnInputSafely(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        // Large streams keep qpdf reading input 0 lazily while it writes, so a
        // direct write to the aliased output would truncate data mid-read.
        Files.write(a, largePdf(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));

        // output aliases input 0: qpdf must not truncate a file it is reading.
        PdfMerge.mergeFilesToFile(List.of(a, b), a);

        byte[] merged = Files.readAllBytes(a);
        assertEquals(5, PdfVerifier.pageCount(merged, "in-place merge"));
        assertTrue(merged.length > 1_000_000,
                "merged output lost the large image stream (size " + merged.length + ")");
        assertEquals(3, PdfVerifier.pageCount(Files.readAllBytes(b), "input b must be untouched"));
    }

    @Test
    void extractPageRangeToFileCanReplaceTheInputSafely(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");
        Path in = tmp.resolve("in.pdf");
        Files.write(in, largePdf(4));

        // output aliases the input: extraction must stage before replacing.
        PdfSplit.extractPageRangeToFile(in, 1, 3, in);

        byte[] part = Files.readAllBytes(in);
        assertEquals(3, PdfVerifier.pageCount(part, "in-place extract"));
        assertTrue(part.length > 1_000_000,
                "extracted output lost the large image stream (size " + part.length + ")");
    }

    /** Multi-megabyte input: qpdf must re-read its streams lazily while writing. */
    private static byte[] largePdf(int pages) throws Exception {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(1600, 1600, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42);
        for (int y = 0; y < 1600; y++) {
            for (int x = 0; x < 1600; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image =
                    org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
                            .createFromByteArray(doc, png.toByteArray(), "big");
            for (int i = 0; i < pages; i++) {
                org.apache.pdfbox.pdmodel.PDPage page =
                        new org.apache.pdfbox.pdmodel.PDPage(
                                org.apache.pdfbox.pdmodel.common.PDRectangle.LETTER);
                doc.addPage(page);
                try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                        new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    cs.drawImage(image, 0, 0, 612, 792);
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void fileModeFailsLoudlyWhenNativeMergeCannotRun(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        Path a = tmp.resolve("a.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Path bad = tmp.resolve("bad.pdf");
        Files.write(bad, "not a pdf".getBytes(StandardCharsets.US_ASCII));

        StorageOptions file = StorageOptions.builder().file().build();
        JPDFiumException ex = assertThrows(JPDFiumException.class,
                () -> PdfMerge.mergeFiles(List.of(a, bad), file));
        assertTrue(ex.getMessage().contains("file-backed"),
                "FILE mode must fail loudly instead of falling back to the heap: "
                        + ex.getMessage());
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
        List<Path> inputs = Collections.nCopies(8, src);

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
