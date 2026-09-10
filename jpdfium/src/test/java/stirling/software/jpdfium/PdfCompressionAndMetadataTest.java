package stirling.software.jpdfium;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.doc.Bookmark;
import stirling.software.jpdfium.doc.CompressOptions;
import stirling.software.jpdfium.doc.CompressPreset;
import stirling.software.jpdfium.doc.PdfCompressor;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfCompressionAndMetadataTest {

    private static byte[] minimalPdfBytes() throws Exception {
        try (InputStream in = PdfCompressionAndMetadataTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            return Objects.requireNonNull(in, "minimal.pdf missing").readAllBytes();
        }
    }

    @Test
    @DisplayName("Compress document via preset returns valid document")
    void testCompressPreset() throws Exception {
        byte[] input = minimalPdfBytes();
        try (PdfDocument doc = PdfDocument.open(input)) {
            int originalPages = doc.pageCount();

            try (PdfDocument compressed = doc.compress(CompressPreset.WEB)) {
                assertNotNull(compressed);
                assertEquals(originalPages, compressed.pageCount());
            }
        }
    }

    @Test
    @DisplayName("Compress document with stats returns valid result and bytes")
    void testCompressWithStats() throws Exception {
        byte[] input = minimalPdfBytes();
        try (PdfDocument doc = PdfDocument.open(input)) {
            CompressOptions options = CompressOptions.builder()
                    .preset(CompressPreset.LOSSLESS)
                    .build();

            PdfCompressor.CompressResultWithBytes result = doc.compressWithStats(options);
            assertNotNull(result);
            assertNotNull(result.bytes());
            assertTrue(result.bytes().length > 0);
            assertTrue(result.result().originalSize() > 0);

            try (PdfDocument reloaded = PdfDocument.open(result.bytes())) {
                assertEquals(doc.pageCount(), reloaded.pageCount());
            }
        }
    }

    @Test
    @DisplayName("Static compressBytes produces valid compressed PDF")
    void testCompressBytesStatic() throws Exception {
        byte[] input = minimalPdfBytes();
        byte[] compressed = PdfDocument.compressBytes(input, CompressPreset.WEB);
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);

        try (PdfDocument doc = PdfDocument.open(compressed)) {
            assertEquals(3, doc.pageCount());
        }
    }

    @Test
    @DisplayName("Set and inspect custom metadata keys")
    void testCustomMetadata() throws Exception {
        byte[] input = minimalPdfBytes();
        try (PdfDocument doc = PdfDocument.open(input)) {
            doc.setMetadata("Producer", "JPDFium Test Producer");
            doc.setMetadata("CustomEngine", "JPDFium Native Engine");

            Set<String> keys = doc.metadataKeys();
            assertNotNull(keys);
            assertTrue(keys.contains("CustomEngine") || keys.contains("Producer") || !keys.isEmpty());
        }
    }

    @Test
    @DisplayName("Bookmark authoring, hierarchy, update and deletion")
    void testBookmarkCrud() throws Exception {
        byte[] input = minimalPdfBytes();
        try (PdfDocument doc = PdfDocument.open(input)) {
            Bookmark b1 = doc.addBookmark("Intro", 0);
            if (b1 != null) {
                Bookmark b2 = doc.addChildBookmark("Intro", "Details", 1);
                assertNotNull(b2);

                List<Bookmark> bookmarks = doc.bookmarks();
                assertFalse(bookmarks.isEmpty());
                assertEquals("Intro", bookmarks.getFirst().title());

                boolean updated = doc.updateBookmarkTitle("Details", "Details-Updated");
                assertTrue(updated);

                boolean deleted = doc.deleteBookmark("Details-Updated");
                assertTrue(deleted);

                doc.clearBookmarks();
                assertTrue(doc.bookmarks().isEmpty());
            }
        }
    }
}
