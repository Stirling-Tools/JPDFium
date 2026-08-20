package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfDocumentFactoryTest {

    @BeforeAll
    static void setUp() {
        NativeLoader.ensureLoaded();
    }

    @Test
    void testLoadFromVariousSources(@TempDir Path tempDir) throws IOException {
        byte[] pdfBytes = SyntheticPdfFactory.singlePageWithText("Factory Test Page");
        int expectedPages = NativeRuntime.isStub() ? 3 : 1;

        // 1. From byte[]
        try (PdfDocument doc = PdfDocumentFactory.load(pdfBytes)) {
            assertNotNull(doc);
            assertEquals(expectedPages, doc.pageCount());
        }

        // 2. From InputStream
        try (ByteArrayInputStream in = new ByteArrayInputStream(pdfBytes);
             PdfDocument doc = PdfDocumentFactory.load(in)) {
            assertNotNull(doc);
            assertEquals(expectedPages, doc.pageCount());
        }

        // 3. From Path
        Path pdfFile = tempDir.resolve("test_factory.pdf");
        Files.write(pdfFile, pdfBytes);
        try (PdfDocument doc = PdfDocumentFactory.load(pdfFile)) {
            assertNotNull(doc);
            assertEquals(expectedPages, doc.pageCount());
        }

        // 4. From File
        File file = pdfFile.toFile();
        try (PdfDocument doc = PdfDocumentFactory.load(file)) {
            assertNotNull(doc);
            assertEquals(expectedPages, doc.pageCount());
        }

        // 5. From ByteBuffer
        ByteBuffer buffer = ByteBuffer.wrap(pdfBytes);
        try (PdfDocument doc = PdfDocumentFactory.load(buffer)) {
            assertNotNull(doc);
            assertEquals(expectedPages, doc.pageCount());
        }

        // 6. Create Empty
        try (PdfDocument doc = PdfDocumentFactory.createNew()) {
            assertNotNull(doc);
        }
    }

    @Test
    void testSaveHelpers(@TempDir Path tempDir) throws IOException {
        byte[] pdfBytes = SyntheticPdfFactory.singlePageWithText("Save Test Page");

        try (PdfDocument doc = PdfDocumentFactory.load(pdfBytes)) {
            // Save to bytes
            byte[] saved = PdfDocumentFactory.saveToBytes(doc);
            assertNotNull(saved);
            assertTrue(saved.length > 0);

            // Save to stream
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfDocumentFactory.saveToStream(doc, out);
            assertTrue(out.size() > 0);

            // Save to path
            Path savedPath = tempDir.resolve("saved.pdf");
            PdfDocumentFactory.saveToFile(doc, savedPath);
            assertTrue(Files.exists(savedPath));
            assertTrue(Files.size(savedPath) > 0);

            // Save to file
            File savedFile = tempDir.resolve("saved_file.pdf").toFile();
            PdfDocumentFactory.saveToFile(doc, savedFile);
            assertTrue(savedFile.exists());
            assertTrue(savedFile.length() > 0);
        }
    }

    @Test
    void testMergeAndSplitMethods(@TempDir Path tempDir) throws IOException {
        assumeTrue(NativeRuntime.isFull(), "PDF merge and split methods require real PDFium native library");

        byte[] docA = SyntheticPdfFactory.singlePageWithText("Doc A");
        byte[] docB = SyntheticPdfFactory.singlePageWithText("Doc B");

        try (PdfDocument dA = PdfDocumentFactory.load(docA);
             PdfDocument dB = PdfDocumentFactory.load(docB)) {
            // Merge
            try (PdfDocument merged = PdfDocumentFactory.merge(java.util.List.of(dA, dB))) {
                assertNotNull(merged);
                assertEquals(2, merged.pageCount());

                // Extract
                try (PdfDocument p0 = merged.extractPages(0)) {
                    assertNotNull(p0);
                    assertEquals(1, p0.pageCount());
                }

                try (PdfDocument range = merged.extractPageRange(0, 1)) {
                    assertNotNull(range);
                    assertEquals(2, range.pageCount());
                }

                // Split
                java.util.List<PdfDocument> parts = merged.splitEveryNPages(1);
                assertNotNull(parts);
                assertEquals(2, parts.size());
                parts.forEach(PdfDocument::close);
            }
        }
    }

    @Test
    void testNullValidation() {
        assertThrows(IllegalArgumentException.class, () -> PdfDocumentFactory.load((byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> PdfDocumentFactory.load((Path) null));
        assertThrows(IllegalArgumentException.class, () -> PdfDocumentFactory.load((File) null));
        assertThrows(IllegalArgumentException.class, () -> PdfDocumentFactory.load((ByteBuffer) null));
        assertThrows(IllegalArgumentException.class, () -> PdfDocumentFactory.saveToBytes(null));
        assertThrows(IllegalArgumentException.class, () -> PdfDocumentFactory.saveToFile(null, Path.of("a.pdf")));
    }
}
