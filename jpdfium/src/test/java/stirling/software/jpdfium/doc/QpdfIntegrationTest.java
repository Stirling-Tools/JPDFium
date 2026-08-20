package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.panama.QpdfLib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QpdfIntegrationTest {

    @Test
    void testQpdfLibSupportStatus() {
        // Assert that calling isSupported does not throw exceptions
        boolean supported = QpdfLib.isSupported();
        assertEquals(supported, PdfOptimizer.isSupported());
        assertEquals(supported, PdfSanitizer.isSupported());
        assertEquals(supported, PdfMerger.isSupported());
        assertEquals(supported, PdfLinearizer.isSupported());
        assertEquals(supported, PdfStreamOptimizer.isSupported());
    }

    @Test
    void testQpdfLibNullAndEmptyInputs() {
        assertNull(QpdfLib.optimize(null, 0, -1, -1, -1, -1));
        assertNull(QpdfLib.optimize(new byte[0], 0, -1, -1, -1, -1));

        assertNull(QpdfLib.sanitize(null, 0));
        assertNull(QpdfLib.sanitize(new byte[0], 0));

        assertNull(QpdfLib.merge(null));
        assertNull(QpdfLib.merge(List.of()));

        assertNull(QpdfLib.extractPages(null, new int[]{0}));
        assertNull(QpdfLib.extractPages(new byte[0], new int[]{0}));
        assertNull(QpdfLib.extractPages(new byte[]{1, 2, 3}, null));
        assertNull(QpdfLib.extractPages(new byte[]{1, 2, 3}, new int[0]));

        assertNull(QpdfLib.encrypt(null, "pass", "pass", PdfSecurity.PERM_ALL, 256));
        assertNull(QpdfLib.encrypt(new byte[0], "pass", "pass", PdfSecurity.PERM_ALL, 256));

        assertNull(QpdfLib.decrypt(null, "pass"));
        assertNull(QpdfLib.decrypt(new byte[0], "pass"));
    }

    @Test
    void testPdfOptimizerStructuralPass(@TempDir Path tempDir) throws Exception {
        byte[] synth = SyntheticPdfFactory.createDiverse(3);

        if (PdfOptimizer.isSupported()) {
            byte[] normalized = PdfOptimizer.normalizeContent(synth);
            assertNotNull(normalized);
            assertTrue(normalized.length > 0);

            byte[] compressed = PdfOptimizer.compress(synth, 9, true, true);
            assertNotNull(compressed);
            assertTrue(compressed.length > 0);

            Path in = tempDir.resolve("in.pdf");
            Path out = tempDir.resolve("opt.pdf");
            Files.write(in, synth);

            PdfOptimizer.optimize(in, out, PdfOptimizer.LINEARIZE, PdfOptimizer.DEFAULT,
                    PdfOptimizer.OBJECT_STREAMS_GENERATE, PdfOptimizer.STREAM_DATA_COMPRESS,
                    PdfOptimizer.DECODE_LEVEL_GENERALIZED);
            assertTrue(Files.exists(out));
            assertTrue(Files.size(out) > 0);
        }
    }

    @Test
    void testPdfSanitizerAllFlags(@TempDir Path tempDir) throws Exception {
        byte[] synth = SyntheticPdfFactory.createDiverse(2);

        if (PdfSanitizer.isSupported()) {
            int allFlags = PdfSanitizer.METADATA | PdfSanitizer.INFO | PdfSanitizer.STRUCTURE
                    | PdfSanitizer.JAVASCRIPT | PdfSanitizer.ATTACHMENTS | PdfSanitizer.ACROFORM
                    | PdfSanitizer.FLATTEN;

            byte[] sanitized = PdfSanitizer.sanitize(synth, allFlags);
            assertNotNull(sanitized);
            assertTrue(sanitized.length > 0);

            Path in = tempDir.resolve("san_in.pdf");
            Path out = tempDir.resolve("san_out.pdf");
            Files.write(in, synth);

            PdfSanitizer.sanitize(in, out, allFlags);
            assertTrue(Files.exists(out));
            assertTrue(Files.size(out) > 0);
        }
    }

    @Test
    void testPdfMergerMultipleDocuments(@TempDir Path tempDir) throws Exception {
        byte[] doc1 = SyntheticPdfFactory.singlePageWithText("Page A");
        byte[] doc2 = SyntheticPdfFactory.singlePageWithText("Page B");
        byte[] doc3 = SyntheticPdfFactory.singlePageWithText("Page C");

        if (PdfMerger.isSupported()) {
            byte[] merged = PdfMerger.mergeBytes(List.of(doc1, doc2, doc3));
            assertNotNull(merged);
            assertTrue(merged.length > 0);

            try (PdfDocument d1 = PdfDocument.open(doc1);
                 PdfDocument d2 = PdfDocument.open(doc2)) {
                byte[] mergedDocs = PdfMerger.mergeDocuments(d1, d2);
                assertNotNull(mergedDocs);
                assertTrue(mergedDocs.length > 0);
            }

            Path p1 = tempDir.resolve("p1.pdf");
            Path p2 = tempDir.resolve("p2.pdf");
            Path p3 = tempDir.resolve("p3.pdf");
            Path out = tempDir.resolve("merged_all.pdf");
            Files.write(p1, doc1);
            Files.write(p2, doc2);
            Files.write(p3, doc3);

            PdfMerger.merge(List.of(p1, p2, p3), out);
            assertTrue(Files.exists(out));
            assertTrue(Files.size(out) > 0);
        }
    }

    @Test
    void testQpdfExtractPages() throws Exception {
        byte[] synth = SyntheticPdfFactory.createDiverse(4);

        if (QpdfLib.isSupported()) {
            byte[] extracted = QpdfLib.extractPages(synth, new int[]{0, 2});
            assertNotNull(extracted);
            assertTrue(extracted.length > 0);
        }
    }

    @Test
    void testPdfSecurityEncryptDecryptAES128And256(@TempDir Path tempDir) throws Exception {
        byte[] synth = SyntheticPdfFactory.singlePageWithText("Secret Confidential Data");

        if (PdfSecurity.isSupported()) {
            // 256-bit AES (R6)
            byte[] enc256 = PdfSecurity.encryptBytes(synth, "userSecret", "ownerSecret",
                    PdfSecurity.PERM_PRINT_HIGH | PdfSecurity.PERM_EXTRACT, 256);
            assertNotNull(enc256);
            assertTrue(enc256.length > 0);

            byte[] dec256 = PdfSecurity.decryptBytes(enc256, "ownerSecret");
            assertNotNull(dec256);
            assertTrue(dec256.length > 0);

            // 128-bit AES (R5)
            byte[] enc128 = PdfSecurity.encryptBytes(synth, "userSecret", "ownerSecret",
                    PdfSecurity.PERM_PRINT_LOW, 128);
            assertNotNull(enc128);
            assertTrue(enc128.length > 0);

            byte[] dec128 = PdfSecurity.decryptBytes(enc128, "ownerSecret");
            assertNotNull(dec128);
            assertTrue(dec128.length > 0);
        }
    }

    @Test
    void testLinearizerAndStreamOptimizer(@TempDir Path tempDir) throws Exception {
        byte[] synth = SyntheticPdfFactory.createDiverse(2);
        Path in = tempDir.resolve("linear_in.pdf");
        Path outLinear = tempDir.resolve("linear_out.pdf");
        Path outOpt = tempDir.resolve("stream_opt.pdf");
        Path outCompact = tempDir.resolve("stream_compact.pdf");
        Files.write(in, synth);

        if (PdfLinearizer.isSupported()) {
            PdfLinearizer.linearize(in, outLinear);
            assertTrue(Files.exists(outLinear));
        }

        if (PdfStreamOptimizer.isSupported()) {
            PdfStreamOptimizer.optimize(in, outOpt);
            assertTrue(Files.exists(outOpt));

            PdfStreamOptimizer.compact(in, outCompact);
            assertTrue(Files.exists(outCompact));
        }
    }
}
