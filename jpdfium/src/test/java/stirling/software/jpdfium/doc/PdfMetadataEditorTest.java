package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PdfTextExtractor;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link PdfMetadataEditor}: /Info entries, custom keys,
 * /Trapped, and XMP replacement on both plain and object-stream PDFs.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfMetadataEditorTest {

    @TempDir
    Path tempDir;

    private static Path resource(String path) throws Exception {
        URL url = PdfMetadataEditorTest.class.getResource(path);
        assertNotNull(url, path + " test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void setsStandardCustomAndTrappedMetadata() throws Exception {
        Path output = tempDir.resolve("metadata.pdf");

        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            MetadataUpdate update = doc.editMetadata()
                    .title("Quarterly report \u2013 R\u00e9sum\u00e9")
                    .author("Finance")
                    .subject("Q3")
                    .keywords("finance, report")
                    .creator("JPDFium test")
                    .producer("JPDFium")
                    .custom("Department", "Engineering")
                    .custom("DocumentId", "DOC-42")
                    .trapped(TrappedStatus.TRUE)
                    .apply();

            assertEquals(6, update.infoFieldsSet());
            assertEquals(2, update.customFieldsSet());
            assertTrue(update.trappedChanged());
            assertFalse(update.xmpChanged());
            doc.save(output);
        }

        try (PdfDocument doc = PdfDocument.open(output)) {
            PdfMetadata metadata = PdfMetadata.of(doc);
            assertEquals(Optional.of("Quarterly report \u2013 R\u00e9sum\u00e9"), metadata.title());
            assertEquals(Optional.of("Finance"), metadata.author());
            assertEquals(Optional.of("Q3"), metadata.subject());
            assertEquals(Optional.of("finance, report"), metadata.keywords());
            assertEquals(Optional.of("JPDFium test"), metadata.creator());
            assertEquals(Optional.of("JPDFium"), metadata.producer());
            assertEquals(Map.of("Department", "Engineering", "DocumentId", "DOC-42"),
                    metadata.custom());
            assertEquals(TrappedStatus.TRUE, metadata.trapped());
            assertEquals(Optional.of("Engineering"), doc.metadata("Department"));
            assertEquals("Engineering", doc.metadata().get("Department"));
        }
    }

    @Test
    void removesStandardCustomAndXmpMetadata() throws Exception {
        Path withMetadata = tempDir.resolve("with-metadata.pdf");
        Path cleaned = tempDir.resolve("cleaned.pdf");

        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            doc.editMetadata()
                    .title("Remove me")
                    .custom("RemoveMe", "yes")
                    .xmp(xmp("Remove me"))
                    .apply();
            doc.save(withMetadata);
        }

        try (PdfDocument doc = PdfDocument.open(withMetadata)) {
            assertEquals(Optional.of("Remove me"), PdfMetadata.of(doc).title());
            assertTrue(PdfMetadata.of(doc).xmp().isPresent());

            MetadataUpdate update = doc.editMetadata()
                    .remove(MetadataTag.TITLE)
                    .removeCustom("RemoveMe")
                    .removeXmp()
                    .apply();
            assertEquals(1, update.infoFieldsRemoved());
            assertEquals(1, update.customFieldsRemoved());
            assertTrue(update.xmpChanged());
            doc.save(cleaned);
        }

        try (PdfDocument doc = PdfDocument.open(cleaned)) {
            PdfMetadata metadata = PdfMetadata.of(doc);
            assertTrue(metadata.title().isEmpty());
            assertTrue(metadata.custom().isEmpty());
            assertTrue(metadata.xmp().isEmpty());
        }
    }

    @Test
    void replacesXmpOnObjectStreamDocumentWithoutLosingContent() throws Exception {
        Path source = resource("/pdfs/general/irs_f1040.pdf");
        Path output = tempDir.resolve("compressed-xmp.pdf");
        String packet = xmp("Compressed document \u2013 R\u00e9sum\u00e9");

        try (PdfDocument doc = PdfDocument.open(source)) {
            int pages = doc.pageCount();
            String originalText = PdfTextExtractor.extractPage(doc, 0).plainText();
            assertTrue(originalText.contains("1040"));

            doc.editMetadata()
                    .custom("Department", "Tax")
                    .xmp(packet)
                    .apply();
            doc.save(output);

            assertEquals(pages, doc.pageCount());
        }

        String sourceRaw = Files.readString(source, StandardCharsets.ISO_8859_1);
        assertTrue(sourceRaw.contains("/ObjStm"), "fixture must use object streams");
        String raw = Files.readString(output, StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("/FlateDecode"),
                "content streams must remain compressed after the rewrite");
        // PDFium's save path expands object streams; qpdf regenerates them only
        // for the in-memory rewrite. The source assertion keeps this test on the
        // object-stream path, while the size guard covers the compression pass.

        try (PdfDocument doc = PdfDocument.open(output)) {
            PdfMetadata metadata = PdfMetadata.of(doc);
            assertEquals(Optional.of(packet), metadata.xmp());
            assertEquals("Tax", metadata.custom().get("Department"));
            assertEquals(2, doc.pageCount());
            assertTrue(PdfTextExtractor.extractPage(doc, 0).plainText().contains("1040"),
                    "compressed content streams must survive the metadata rewrite");
        }
    }

    @Test
    void compressedDocumentDoesNotBalloon() throws Exception {
        Path source = resource("/pdfs/general/irs_w2.pdf");
        Path output = tempDir.resolve("compressed-size.pdf");
        long originalSize = Files.size(source);

        try (PdfDocument doc = PdfDocument.open(source)) {
            doc.editMetadata()
                    .title("Compressed size")
                    .xmp(xmp("Compressed size"))
                    .applyAndSave(output);
        }

        long outputSize = Files.size(output);
        assertTrue(outputSize < originalSize * 3 / 2,
                () -> "metadata save grew %,d bytes to %,d bytes"
                        .formatted(originalSize, outputSize));

        try (PdfDocument doc = PdfDocument.open(output)) {
            assertEquals(11, doc.pageCount());
            assertTrue(PdfTextExtractor.extractPage(doc, 0).plainText().contains("electronically"),
                    "page content must survive the metadata save");
        }
    }

    @Test
    void xmpRoundTripsOnPlainDocument() throws Exception {
        Path source = resource("/pdfs/general/minimal.pdf");
        Path output = tempDir.resolve("xmp.pdf");
        String packet = xmp("Plain document");

        try (PdfDocument doc = PdfDocument.open(source)) {
            assertTrue(PdfMetadata.of(doc).xmp().isEmpty(), "fixture should have no XMP");
            doc.editMetadata().xmp(packet).applyAndSave(output);
        }

        try (PdfDocument doc = PdfDocument.open(output)) {
            assertEquals(Optional.of(packet), PdfMetadata.of(doc).xmp());
            assertTrue(PdfTextExtractor.extractPage(doc, 0).plainText().contains("Hello World"));
        }
    }

    @Test
    void metadataReadsDoNotMarkDocumentModified() throws Exception {
        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            assertFalse(doc.isContentModified());
            doc.metadata();
            PdfMetadata.of(doc).custom();
            PdfMetadata.of(doc).xmp();
            assertFalse(doc.isContentModified(),
                    "reading metadata must not mark the document as edited");
        }
    }

    @Test
    void emptyEditReturnsWithoutTouchingTheDocument() throws Exception {
        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            assertFalse(doc.isContentModified());
            assertSame(MetadataUpdate.NONE, doc.editMetadata().apply());
            assertFalse(doc.isContentModified());
        }
    }

    @Test
    void documentBackedReaderTracksHandleReloadsAndRejectsClose() throws Exception {
        PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"));
        try {
            PdfMetadata metadata = PdfMetadata.of(doc);
            String packet = xmp("Reloaded reader");

            doc.editMetadata().title("Reloaded title").xmp(packet).apply();
            assertEquals(Optional.of("Reloaded title"), metadata.title());
            assertEquals(Optional.of(packet), metadata.xmp());

            doc.close();
            assertThrows(IllegalStateException.class, metadata::title);
        } finally {
            doc.close();
        }
    }

    @Test
    void rejectsInvalidCustomKeysAndBlankXmp() throws Exception {
        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> doc.editMetadata().custom("Title", "reserved"));
            assertThrows(IllegalArgumentException.class,
                    () -> doc.editMetadata().custom("Bad Key", "value"));
            assertThrows(IllegalArgumentException.class,
                    () -> doc.editMetadata().custom("", "value"));
            assertThrows(IllegalArgumentException.class,
                    () -> doc.editMetadata().xmp("   "));
        }
    }

    private static String xmp(String title) {
        return """
                <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">%s</rdf:li></rdf:Alt></dc:title>
                  </rdf:Description>
                 </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>
                """.formatted(title);
    }
}
