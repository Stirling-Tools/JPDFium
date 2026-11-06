package stirling.software.jpdfium;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.doc.ActionType;
import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.PdfBookmarkEditor;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.doc.PdfSecurity;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

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
    void nonDurableFileOutputsMatchDurableOnes(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");

        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));
        StorageOptions ephemeral = StorageOptions.builder().durable(false).build();

        Path durableMerge = tmp.resolve("durable-merge.pdf");
        Path ephemeralMerge = tmp.resolve("ephemeral-merge.pdf");
        PdfMerge.mergeFilesToFile(List.of(a, b), durableMerge);
        PdfMerge.mergeFilesToFile(List.of(a, b), ephemeralMerge, ephemeral);
        byte[] durableBytes = Files.readAllBytes(durableMerge);
        byte[] ephemeralBytes = Files.readAllBytes(ephemeralMerge);
        assertEquals(5, PdfVerifier.pageCount(ephemeralBytes, "non-durable merge"));
        for (int i = 0; i < 5; i++) {
            assertEquals(PdfVerifier.pageText(durableBytes, i, "durable merge"),
                    PdfVerifier.pageText(ephemeralBytes, i, "non-durable merge"));
        }

        Path part = tmp.resolve("part.pdf");
        Files.writeString(part, "stale");
        PdfSplit.extractPageRangeToFile(b, 1, 2, part, ephemeral);
        byte[] extracted = Files.readAllBytes(part);
        byte[] source = Files.readAllBytes(b);
        assertEquals(2, PdfVerifier.pageCount(extracted, "non-durable extract"));
        for (int i = 0; i < 2; i++) {
            assertEquals(PdfVerifier.pageText(source, i + 1, "source"),
                    PdfVerifier.pageText(extracted, i, "non-durable extract"));
        }
        assertNoStagingFiles(tmp);
    }

    @Test
    void durabilityChoiceReachesPublication(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");
        assumeTrue(tmp.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "permission mirroring is POSIX-only");

        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));
        StorageOptions ephemeral = StorageOptions.builder().durable(false).build();
        Set<PosixFilePermission> shared = PosixFilePermissions.fromString("rw-r--r--");
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");

        Path durableMerge = existingFile(tmp, "durable-merge.pdf", shared);
        Path ephemeralMerge = existingFile(tmp, "ephemeral-merge.pdf", shared);
        PdfMerge.mergeFilesToFile(List.of(a, b), durableMerge);
        PdfMerge.mergeFilesToFile(List.of(a, b), ephemeralMerge, ephemeral);
        assertEquals(shared, Files.getPosixFilePermissions(durableMerge));
        assertEquals(ownerOnly, Files.getPosixFilePermissions(ephemeralMerge));

        Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("rw-rw-rw-"));
        Path durableCopy = existingFile(tmp, "durable-copy.pdf", shared);
        Path ephemeralCopy = existingFile(tmp, "ephemeral-copy.pdf", shared);
        PdfMerge.mergeFilesToFile(List.of(a), durableCopy);
        PdfMerge.mergeFilesToFile(List.of(a), ephemeralCopy, ephemeral);
        assertEquals(shared, Files.getPosixFilePermissions(durableCopy));
        assertEquals(ownerOnly, Files.getPosixFilePermissions(ephemeralCopy));
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(ephemeralCopy));

        Path durablePart = existingFile(tmp, "durable-part.pdf", shared);
        Path ephemeralPart = existingFile(tmp, "ephemeral-part.pdf", shared);
        PdfSplit.extractPageRangeToFile(b, 0, 1, durablePart);
        PdfSplit.extractPageRangeToFile(b, 0, 1, ephemeralPart, ephemeral);
        assertEquals(shared, Files.getPosixFilePermissions(durablePart));
        assertEquals(ownerOnly, Files.getPosixFilePermissions(ephemeralPart));
        assertNoStagingFiles(tmp);
    }

    @Test
    void fileOutputsReplaceTheTargetOfASymlink(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");

        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));
        Path targets = Files.createDirectory(tmp.resolve("targets"));
        Path links = Files.createDirectory(tmp.resolve("links"));
        Path mergeTarget = Files.writeString(targets.resolve("merged.pdf"), "stale");
        Path partTarget = Files.writeString(targets.resolve("part.pdf"), "stale");
        Path mergeLink;
        Path partLink;
        try {
            mergeLink = Files.createSymbolicLink(links.resolve("merged.pdf"), mergeTarget);
            partLink = Files.createSymbolicLink(links.resolve("part.pdf"), partTarget);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
            return;
        }

        PdfMerge.mergeFilesToFile(List.of(a, b), mergeLink, StorageOptions.builder().durable(false).build());
        PdfSplit.extractPageRangeToFile(b, 1, 2, partLink);

        assertTrue(Files.isSymbolicLink(mergeLink));
        assertTrue(Files.isSymbolicLink(partLink));
        assertEquals(5, PdfVerifier.pageCount(Files.readAllBytes(mergeTarget), "merge through symlink"));
        assertEquals(2, PdfVerifier.pageCount(Files.readAllBytes(partTarget), "extract through symlink"));
        assertNoStagingFiles(links);
        assertNoStagingFiles(targets);
    }

    private static Path existingFile(Path dir, String name, Set<PosixFilePermission> permissions)
            throws IOException {
        Path file = Files.writeString(dir.resolve(name), "stale");
        Files.setPosixFilePermissions(file, permissions);
        return file;
    }

    private static void assertNoStagingFiles(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith(".jpdfium-")),
                    "staging files must not be left behind");
        }
    }

    @Test
    void qpdfOutputsKeepEveryStreamEncoding(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");
        assumeTrue(PdfSecurity.isCopyEncryptionSupported(), "needs the native encryption-copy API");

        Path source = tmp.resolve("mixed.pdf");
        Files.write(source, rawAndFlatePages());
        byte[] sourceBytes = Files.readAllBytes(source);
        List<PageStream> expected = pageStreams(sourceBytes, null);
        assertNull(expected.get(0).filters());
        assertEquals(COSName.FLATE_DECODE, expected.get(1).filters());

        Path merged = tmp.resolve("merged.pdf");
        PdfMerge.mergeFilesToFile(List.of(source, source), merged);
        assertPagesKeepEncoding(expected, List.of(0, 1, 0, 1), Files.readAllBytes(merged), null);

        Path part = tmp.resolve("part.pdf");
        PdfSplit.extractPageRangeToFile(source, 1, 1, part);
        assertPagesKeepEncoding(expected, List.of(1), Files.readAllBytes(part), null);

        assertPagesKeepEncoding(expected, List.of(0, 1, 0, 1),
                QpdfLib.merge(List.of(sourceBytes, sourceBytes)), null);
        assertPagesKeepEncoding(expected, List.of(1, 0),
                QpdfLib.extractPages(sourceBytes, new int[] {1, 0}), null);

        Path protection = tmp.resolve("protection.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner", "user", new AccessPermission());
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);
            doc.save(protection.toFile());
        }
        Path protectedOut = tmp.resolve("protected.pdf");
        PdfSecurity.copyEncryption(source, protectedOut, protection, "owner");
        assertPagesKeepEncoding(expected, List.of(0, 1), Files.readAllBytes(protectedOut), "user");
    }

    private record PageStream(COSBase filters, byte[] encoded, byte[] decoded) {}

    private static byte[] rawAndFlatePages() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage raw = new PDPage(PDRectangle.A4);
            doc.addPage(raw);
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, raw, PDPageContentStream.AppendMode.OVERWRITE, false)) {
                cs.addRect(10, 10, 100, 100);
                cs.fill();
            }
            PDPage flate = new PDPage(PDRectangle.A4);
            doc.addPage(flate);
            byte[] content = "0 0 1 rg 20 20 200 200 re f\n".getBytes(StandardCharsets.US_ASCII);
            flate.setContents(new PDStream(doc, new ByteArrayInputStream(content), COSName.FLATE_DECODE));
            doc.save(out, CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        }
    }

    private static List<PageStream> pageStreams(byte[] pdf, String password) throws IOException {
        List<PageStream> streams = new ArrayList<>();
        try (PDDocument doc = password == null ? Loader.loadPDF(pdf) : Loader.loadPDF(pdf, password)) {
            for (PDPage page : doc.getPages()) {
                COSStream contents = (COSStream) page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
                try (InputStream encoded = contents.createRawInputStream();
                     InputStream decoded = contents.createInputStream()) {
                    streams.add(new PageStream(contents.getFilters(),
                            encoded.readAllBytes(), decoded.readAllBytes()));
                }
            }
        }
        return streams;
    }

    private static void assertPagesKeepEncoding(List<PageStream> source, List<Integer> sourcePages,
            byte[] output, String password) throws IOException {
        List<PageStream> actual = pageStreams(output, password);
        assertEquals(sourcePages.size(), actual.size(), "page count");
        for (int i = 0; i < actual.size(); i++) {
            PageStream want = source.get(sourcePages.get(i));
            PageStream got = actual.get(i);
            assertEquals(want.filters(), got.filters(), "filters of output page " + i);
            assertArrayEquals(want.decoded(), got.decoded(), "content of output page " + i);
            if (password == null) {
                assertArrayEquals(want.encoded(), got.encoded(), "encoded bytes of output page " + i);
            }
        }
    }

    @Test
    void nonDurableMergePublishesACompleteFile(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");

        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));

        Path out = tmp.resolve("merged.pdf");
        StorageOptions fast = StorageOptions.builder().durable(false).build();
        PdfMerge.mergeFilesToFile(List.of(a, b), out, fast);
        assertTrue(Files.size(out) > 0, "non-durable merge must still publish the file");
        byte[] bytes = Files.readAllBytes(out);
        assertEquals(5, PdfVerifier.pageCount(bytes, "non-durable merge"));
        try (PdfDocument check = PdfDocument.open(bytes)) {
            assertEquals(5, check.pageCount());
        }
    }

    @Test
    void fileMergeAndExtractKeepRawStreamsRaw(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");

        Path raw = tmp.resolve("raw.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
                    cs.addRect(10, 10, 100, 100);
                    cs.fill();
                }
            }
            doc.save(raw.toFile(), CompressParameters.NO_COMPRESSION);
        }

        Path merged = tmp.resolve("merged.pdf");
        PdfMerge.mergeFilesToFile(List.of(raw, raw), merged);
        Path part = tmp.resolve("part.pdf");
        PdfSplit.extractPageRangeToFile(raw, 1, 1, part);

        for (Path out : List.of(merged, part)) {
            try (PDDocument doc = Loader.loadPDF(out.toFile())) {
                for (PDPage page : doc.getPages()) {
                    COSStream contents = (COSStream) page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
                    assertNull(contents.getFilters(), out.getFileName() + " content stream was re-encoded");
                }
            }
        }
    }

    @Test
    void splitToFilesFastWritesAllParts(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractRangesSupported(), "needs qpdf batch extract symbol");

        Path input = tmp.resolve("fast-source.pdf");
        Files.write(input, SyntheticPdfFactory.createDiverse(5));

        Path dir = tmp.resolve("fast-parts");
        List<Path> parts = PdfSplit.splitToFilesFast(
                input, PdfSplit.SplitStrategy.everyNPages(2), dir);
        assertEquals(3, parts.size());
        int[] pages = {2, 2, 1};
        for (int i = 0; i < parts.size(); i++) {
            assertEquals(pages[i],
                    PdfVerifier.pageCount(Files.readAllBytes(parts.get(i)), "fast part " + i));
        }
    }

    @Test
    void splitToFilesMatchesBatchSplit(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractRangesSupported(), "needs qpdf batch extract symbol");

        Path input = tmp.resolve("source.pdf");
        Files.write(input, SyntheticPdfFactory.createDiverse(6));

        Path dir = tmp.resolve("parts");
        List<Path> parts = PdfSplit.splitToFiles(
                input, PdfSplit.SplitStrategy.everyNPages(2), dir);
        assertEquals(3, parts.size());
        for (int i = 0; i < parts.size(); i++) {
            byte[] bytes = Files.readAllBytes(parts.get(i));
            assertEquals(2, PdfVerifier.pageCount(bytes, "split part " + i));
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

    @Test
    void publishPreservesExistingPermissionsAndUsesUmaskDefaults(@TempDir Path tmp) throws Exception {
        assumePosix();
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");

        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));
        Path input = tmp.resolve("input.pdf");
        Files.write(input, SyntheticPdfFactory.createDiverse(5));
        StorageOptions durableOpts = StorageOptions.defaults();
        Set<PosixFilePermission> custom = PosixFilePermissions.fromString("rw-r-----");
        Set<PosixFilePermission> defaults = defaultCreatePermissions(tmp);

        // Durable merge: existing destination permissions are preserved...
        Path existingMerge = tmp.resolve("existing-merge.pdf");
        Files.write(existingMerge, new byte[] {1, 2, 3});
        Files.setPosixFilePermissions(existingMerge, custom);
        PdfMerge.mergeFilesToFile(List.of(a, b), existingMerge, durableOpts);
        assertEquals(custom, Files.getPosixFilePermissions(existingMerge),
                "durable merge must preserve existing destination permissions");
        assertEquals(5, PdfVerifier.pageCount(Files.readAllBytes(existingMerge), "merge"));

        // ...and a new destination gets the umask default, not the native 0600.
        Path newMerge = tmp.resolve("new-merge.pdf");
        PdfMerge.mergeFilesToFile(List.of(a, b), newMerge, durableOpts);
        assertEquals(defaults, Files.getPosixFilePermissions(newMerge),
                "durable merge must not leave a 0600 destination");

        // Safe split: existing and new part files follow the same rules.
        Path safeDir = tmp.resolve("safe-parts");
        Files.createDirectories(safeDir);
        Path existingPart = safeDir.resolve("part-001.pdf");
        Files.write(existingPart, new byte[] {1, 2, 3});
        Files.setPosixFilePermissions(existingPart, custom);
        List<Path> safeParts = PdfSplit.splitToFiles(
                input, PdfSplit.SplitStrategy.everyNPages(10), safeDir, durableOpts);
        assertEquals(1, safeParts.size());
        assertEquals(custom, Files.getPosixFilePermissions(existingPart),
                "durable split must preserve existing part permissions");

        Path safeNewDir = tmp.resolve("safe-new-parts");
        List<Path> safeNewParts = PdfSplit.splitToFiles(
                input, PdfSplit.SplitStrategy.everyNPages(10), safeNewDir, durableOpts);
        for (Path part : safeNewParts) {
            assertEquals(defaults, Files.getPosixFilePermissions(part),
                    "durable split must not leave a 0600 part");
        }

        // Fast direct split: native output starts at 0600 and must be restored
        // to the destination's permissions (existing) or the umask default.
        Path fastDir = tmp.resolve("fast-parts");
        Files.createDirectories(fastDir);
        Path fastExistingPart = fastDir.resolve("part-001.pdf");
        Files.write(fastExistingPart, new byte[] {1, 2, 3});
        Files.setPosixFilePermissions(fastExistingPart, custom);
        List<Path> fastParts = PdfSplit.splitToFilesFast(
                input, PdfSplit.SplitStrategy.everyNPages(10), fastDir);
        assertEquals(1, fastParts.size());
        assertEquals(custom, Files.getPosixFilePermissions(fastExistingPart),
                "fast split must preserve existing part permissions");

        Path fastNewDir = tmp.resolve("fast-new-parts");
        List<Path> fastNewParts = PdfSplit.splitToFilesFast(
                input, PdfSplit.SplitStrategy.everyNPages(10), fastNewDir);
        for (Path part : fastNewParts) {
            assertEquals(defaults, Files.getPosixFilePermissions(part),
                    "fast split must not leave a 0600 part");
        }
    }

    @Test
    void splitRejectsInputAliasingAnOutputPart(@TempDir Path tmp) throws Exception {
        assumePosix();
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        StorageOptions fast = StorageOptions.builder().durable(false).build();

        Path dir = tmp.resolve("alias");
        Files.createDirectories(dir);
        Path input = dir.resolve("part-001.pdf");
        byte[] original = SyntheticPdfFactory.createDiverse(3);
        Files.write(input, original);

        JPDFiumException safe = assertThrows(JPDFiumException.class,
                () -> PdfSplit.splitToFiles(
                        input, PdfSplit.SplitStrategy.everyNPages(1), dir, fast));
        assertTrue(safe.getMessage().contains("must differ"), safe.getMessage());
        assertArrayEquals(original, Files.readAllBytes(input),
                "aliased safe split must not modify the input");

        JPDFiumException direct = assertThrows(JPDFiumException.class,
                () -> PdfSplit.splitToFilesFast(
                        input, PdfSplit.SplitStrategy.everyNPages(1), dir));
        assertTrue(direct.getMessage().contains("must differ"), direct.getMessage());
        assertArrayEquals(original, Files.readAllBytes(input),
                "aliased fast split must not modify the input");

        // A symlink to the input is the same alias even though the paths differ.
        Path linkDir = tmp.resolve("alias-link");
        Files.createDirectories(linkDir);
        Path source = tmp.resolve("source.pdf");
        Files.write(source, original);
        Path link = linkDir.resolve("part-001.pdf");
        createSymlinkOrSkip(link, source);

        assertThrows(JPDFiumException.class,
                () -> PdfSplit.splitToFiles(
                        source, PdfSplit.SplitStrategy.everyNPages(1), linkDir, fast));
        assertArrayEquals(original, Files.readAllBytes(source),
                "symlink-aliased safe split must not modify the input");
        assertThrows(JPDFiumException.class,
                () -> PdfSplit.splitToFilesFast(
                        source, PdfSplit.SplitStrategy.everyNPages(1), linkDir));
        assertArrayEquals(original, Files.readAllBytes(source),
                "symlink-aliased fast split must not modify the input");
    }

    @Test
    void splitToFilesPreservesBookmarksInTheFallbackPath(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        Path plain = tmp.resolve("plain.pdf");
        Files.write(plain, SyntheticPdfFactory.createDiverse(6));
        Path marked = tmp.resolve("marked.pdf");
        try (PdfDocument doc = PdfDocument.open(plain)) {
            PdfBookmarkEditor.setBookmarks(doc,
                    List.of(goTo("First", 0), goTo("Fourth", 3)), marked);
        }

        StorageOptions fast = StorageOptions.builder().durable(false).build();
        List<Path> safe = PdfSplit.splitToFiles(
                marked, PdfSplit.SplitStrategy.everyNPages(3), tmp.resolve("safe"), fast);
        assertBookmarks(safe);

        List<Path> direct = PdfSplit.splitToFilesFast(
                marked, PdfSplit.SplitStrategy.everyNPages(3), tmp.resolve("direct"));
        assertBookmarks(direct);
    }

    @Test
    void splitFollowsAnExistingPartSymlinkWithoutReplacingIt(@TempDir Path tmp) throws Exception {
        assumePosix();
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        StorageOptions fast = StorageOptions.builder().durable(false).build();
        Path input = tmp.resolve("source.pdf");
        Files.write(input, SyntheticPdfFactory.createDiverse(4));

        assertSymlinkPublish(tmp.resolve("safe"), input, fast, false);
        assertSymlinkPublish(tmp.resolve("direct"), input, fast, true);
    }

    private static void assertSymlinkPublish(Path dir, Path input, StorageOptions options,
                                             boolean direct) throws Exception {
        Files.createDirectories(dir);
        Path real = dir.resolve("real-part.pdf");
        Files.write(real, new byte[] {1, 2, 3});
        Path link = dir.resolve("part-001.pdf");
        createSymlinkOrSkip(link, real);

        List<Path> parts = direct
                ? PdfSplit.splitToFilesFast(input, PdfSplit.SplitStrategy.everyNPages(10), dir)
                : PdfSplit.splitToFiles(input, PdfSplit.SplitStrategy.everyNPages(10), dir, options);
        assertEquals(1, parts.size());
        assertTrue(Files.isSymbolicLink(link), "the part symlink must not be replaced");
        assertEquals(4, PdfVerifier.pageCount(Files.readAllBytes(real),
                "split output through symlink"));
    }

    private static void assertBookmarks(List<Path> parts) throws Exception {
        assertEquals(2, parts.size());
        try (PdfDocument first = PdfDocument.open(parts.get(0));
             PdfDocument second = PdfDocument.open(parts.get(1))) {
            assertEquals("First", first.bookmarks().getFirst().title());
            Bookmark fourth = second.bookmarks().getFirst();
            assertEquals("Fourth", fourth.title());
            assertEquals(0, fourth.pageIndex(), "bookmark must be remapped into its part");
        }
    }

    private static Bookmark goTo(String title, int page) {
        return new Bookmark(title, page, List.of(), ActionType.GOTO,
                Optional.empty(), Optional.empty());
    }

    private static Set<PosixFilePermission> defaultCreatePermissions(Path dir) throws Exception {
        Path probe = dir.resolve("umask-probe-" + UUID.randomUUID());
        Files.createFile(probe);
        try {
            return Files.getPosixFilePermissions(probe);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "symlinks unavailable: " + e.getMessage());
        }
    }

    private static void assumePosix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "needs a POSIX filesystem");
    }

    /** Multi-megabyte input: qpdf must re-read its streams lazily while writing. */
    private static byte[] largePdf(int pages) throws Exception {
        BufferedImage img =
                new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(42);
        for (int y = 0; y < 1600; y++) {
            for (int x = 0; x < 1600; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        try (PDDocument doc =
                new PDDocument()) {
            PDImageXObject image =
                    PDImageXObject
                            .createFromByteArray(doc, png.toByteArray(), "big");
            for (int i = 0; i < pages; i++) {
                PDPage page =
                        new PDPage(
                                PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs =
                        new PDPageContentStream(doc, page)) {
                    cs.drawImage(image, 0, 0, 612, 792);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
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

        // 8 copies of a 40-page doc: big enough that any byte[] round-trip
        // shows up clearly in thread allocations, and that the fixed per-call
        // cost of staging and publishing the output does not.
        Path src = tmp.resolve("src.pdf");
        Files.write(src, SyntheticPdfFactory.createDiverse(40));
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

    @Test
    void documentMergeToFileUsesTheFileBackedPath(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");

        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(3));

        Path merged = tmp.resolve("merged.pdf");
        PdfDocument.merge(List.of(a, b), merged);
        byte[] bytes = Files.readAllBytes(merged);
        assertEquals(5, PdfVerifier.pageCount(bytes, "document merge to file"));
        for (int i = 0; i < 5; i++) {
            PdfVerifier.assertNonEmptyText(bytes, i, "document merge page " + i);
        }

        // Replacing an existing destination must publish a complete file.
        PdfDocument.merge(List.of(b, a), merged);
        assertEquals(5, PdfVerifier.pageCount(Files.readAllBytes(merged), "replaced merge"));

        // A single input still takes the staging copy path.
        Path single = tmp.resolve("single.pdf");
        PdfDocument.merge(List.of(a), single);
        assertEquals(2, PdfVerifier.pageCount(Files.readAllBytes(single), "single input merge"));

        assertThrows(IllegalArgumentException.class,
                () -> PdfDocument.merge(List.of(), merged));
        assertThrows(IllegalArgumentException.class,
                () -> PdfDocument.merge(List.of(a), null));
    }
}
