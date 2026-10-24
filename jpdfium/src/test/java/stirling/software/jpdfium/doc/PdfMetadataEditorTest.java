package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
            String savedXmp = metadata.xmp().orElseThrow();
            assertTrue(savedXmp.contains("Compressed document \u2013 R\u00e9sum\u00e9"));
            assertTrue(savedXmp.contains("pdfuaid:part"),
                    "the fixture's PDF/UA identification must survive");
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

    @Test
    void incrementalSaveKeepsTheSignedBytesAndDigest() throws Exception {
        Path source = resource("/pdfs/general/signed.pdf");
        Path output = tempDir.resolve("signed-with-metadata.pdf");
        byte[] original = Files.readAllBytes(source);
        byte[] digest;

        try (PdfDocument doc = PdfDocument.open(source)) {
            assertTrue(doc.signatureCount() > 0, "fixture must contain a signature");
            digest = doc.signatureDigest(0, 1);
            doc.editMetadata().title("Signed metadata").applyAndSave(output);
        }

        byte[] saved = Files.readAllBytes(output);
        assertTrue(saved.length > original.length,
                "incremental save must append a revision");
        assertArrayEquals(original, Arrays.copyOf(saved, original.length),
                "the signed revision must remain byte-for-byte intact");

        try (PdfDocument doc = PdfDocument.open(output)) {
            assertEquals(1, doc.signatureCount());
            assertArrayEquals(digest, doc.signatureDigest(0, 1),
                    "the signature digest must cover the same signed bytes");
            assertEquals(Optional.of("Signed metadata"), PdfMetadata.of(doc).title());
        }
    }

    @Test
    void xmpReplacementOnSignedDocumentIsRefusedByDefault() throws Exception {
        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/signed.pdf"))) {
            assertThrows(IllegalStateException.class,
                    () -> doc.editMetadata().xmp(xmp("Signed replacement")).apply());
            assertEquals(Optional.empty(), PdfMetadata.of(doc).title());
        }
    }

    @Test
    void replacingXmpKeepsPdfaAndPdfxIdentification() throws Exception {
        Path conformant = tempDir.resolve("conformant.pdf");
        Path output = tempDir.resolve("conformant-edited.pdf");

        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            doc.editMetadata().xmp(conformantXmp("Original")).applyAndSave(conformant);
        }

        try (PdfDocument doc = PdfDocument.open(conformant)) {
            doc.editMetadata().xmp(xmp("Replacement")).applyAndSave(output);
        }

        String saved = Files.readString(output, StandardCharsets.ISO_8859_1);
        assertTrue(saved.contains("pdfaid:part"), "PDF/A part must survive");
        assertTrue(saved.contains("pdfaid:conformance"), "PDF/A conformance must survive");
        assertTrue(saved.contains("GTS_PDFXVersion"), "PDF/X version must survive");
        assertTrue(saved.contains("pdfuaid:part"), "PDF/UA identification must survive");
        assertTrue(saved.contains("pdfuaid:rev"), "PDF/UA revision must survive");
        assertTrue(saved.contains("pdfaExtension:schemas"),
                "PDF/A extension description must survive");
    }

    @Test
    void conformantInfoChangesAreMirroredIntoXmp() throws Exception {
        Path conformant = tempDir.resolve("conformant-info.pdf");
        Path output = tempDir.resolve("conformant-info-edited.pdf");

        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            doc.editMetadata().xmp(conformantXmp("Original")).applyAndSave(conformant);
        }

        try (PdfDocument doc = PdfDocument.open(conformant)) {
            doc.editMetadata().title("Updated title").author("Updated author").applyAndSave(output);
        }

        try (PdfDocument doc = PdfDocument.open(output)) {
            String packet = PdfMetadata.of(doc).xmp().orElseThrow();
            assertTrue(packet.contains("Updated title"));
            assertTrue(packet.contains("Updated author"));
            assertTrue(packet.contains("pdfaid:part"));
            assertEquals(1, occurrences(packet, "<dc:title"));
            assertEquals(1, occurrences(packet, "<dc:creator"));
        }
    }

    @Test
    void removingTitleFromPdfUaDocumentIsRefusedByDefault() throws Exception {
        Path conformant = tempDir.resolve("pdfua-title.pdf");

        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            doc.editMetadata().xmp(conformantXmp("Original")).applyAndSave(conformant);
        }

        try (PdfDocument doc = PdfDocument.open(conformant)) {
            assertThrows(IllegalStateException.class, () -> doc.editMetadata().title(null).apply());
            assertTrue(PdfMetadata.of(doc).xmp().orElseThrow().contains("Original"));
        }
    }

    @Test
    void removingXmpFromConformantDocumentIsRefusedByDefault() throws Exception {
        Path conformant = tempDir.resolve("conformant-remove.pdf");

        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            doc.editMetadata().xmp(conformantXmp("Original")).applyAndSave(conformant);
        }

        try (PdfDocument doc = PdfDocument.open(conformant)) {
            assertThrows(IllegalStateException.class, () -> doc.editMetadata().removeXmp().apply());
            assertTrue(PdfMetadata.of(doc).xmp().orElseThrow().contains("pdfaid:part"));
        }
    }

    @Test
    void conformancePreservationCanBeDisabled() throws Exception {
        Path conformant = tempDir.resolve("conformant-opt-out.pdf");
        Path output = tempDir.resolve("conformant-opt-out-edited.pdf");

        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            doc.editMetadata().xmp(conformantXmp("Original")).applyAndSave(conformant);
        }

        try (PdfDocument doc = PdfDocument.open(conformant)) {
            doc.editMetadata().preserveConformance(false).removeXmp().applyAndSave(output);
        }

        try (PdfDocument doc = PdfDocument.open(output)) {
            assertTrue(PdfMetadata.of(doc).xmp().isEmpty());
        }
    }

    @Test
    void signaturePreservationCanBeDisabled() throws Exception {
        Path output = tempDir.resolve("signed-opt-out.pdf");
        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/signed.pdf"))) {
            doc.editMetadata().preserveSignatures(false).xmp(xmp("Opt-out")).applyAndSave(output);
        }
        try (PdfDocument saved = PdfDocument.open(output)) {
            assertEquals(Optional.of(xmp("Opt-out")), PdfMetadata.of(saved).xmp());
        }
    }

    @Test
    void autoCropMarksTheDocumentModified() throws Exception {
        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/basic-text.pdf"))) {
            int cropped = PdfAutoCrop.cropAll(doc, 0f, false, false);
            assertTrue(cropped > 0, "fixture must have croppable content");
            assertTrue(doc.isContentModified(),
                    "auto-crop must invalidate the file-backed source snapshot");
        }
    }

    @Test
    void laterXmpEditRetainsEarlierInfoChanges() throws Exception {
        Path first = tempDir.resolve("info-first.pdf");
        Path second = tempDir.resolve("info-then-xmp.pdf");
        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            doc.editMetadata().title("First title").applyAndSave(first);
        }
        try (PdfDocument doc = PdfDocument.open(first)) {
            doc.editMetadata().xmp(xmp("Second pass")).applyAndSave(second);
        }
        try (PdfDocument saved = PdfDocument.open(second)) {
            assertEquals(Optional.of("First title"), PdfMetadata.of(saved).title());
            assertEquals(Optional.of(xmp("Second pass")), PdfMetadata.of(saved).xmp());
        }
    }

    @Test
    void xmpRewriteDoesNotDiscardPriorContentEdits() throws Exception {
        Path output = tempDir.resolve("rotated-xmp.pdf");
        try (PdfDocument doc = PdfDocument.open(resource("/pdfs/general/minimal.pdf"))) {
            PdfPageGeometry.setRotation(doc, 0, 90);
            doc.editMetadata().xmp(xmp("Rotated")).applyAndSave(output);
        }
        try (PdfDocument saved = PdfDocument.open(output)) {
            assertEquals(90, saved.getPageRotation(0));
            assertEquals(Optional.of(xmp("Rotated")), PdfMetadata.of(saved).xmp());
        }
    }

    @Test
    void metadataEditOnUserPasswordDocumentUsesRetainedPassword() throws Exception {
        Path encrypted = tempDir.resolve("reader-password.pdf");
        Path output = tempDir.resolve("reader-password-edited.pdf");
        PdfSecurity.encrypt(resource("/pdfs/general/minimal.pdf"), encrypted,
                "reader-pass", "owner-pass", PdfSecurity.PERM_ALL);

        try (PdfDocument doc = PdfDocument.open(encrypted, "reader-pass")) {
            doc.editMetadata()
                    .title("Encrypted title")
                    .xmp(xmp("Encrypted XMP"))
                    .applyAndSave(output);
        }

        try (PdfDocument doc = PdfDocument.open(output, "reader-pass")) {
            assertTrue(DocInfo.analyze(doc).isEncrypted(), "output must stay encrypted");
            assertEquals(Optional.of("Encrypted title"), PdfMetadata.of(doc).title());
            assertEquals(Optional.of(xmp("Encrypted XMP")), PdfMetadata.of(doc).xmp());
        }
    }

    private static String conformantXmp(String title) {
        return """
                <?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">%s</rdf:li></rdf:Alt></dc:title>
                  </rdf:Description>
                  <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
                   <pdfaid:part>2</pdfaid:part>
                   <pdfaid:conformance>B</pdfaid:conformance>
                  </rdf:Description>
                  <rdf:Description rdf:about=""
                      xmlns:pdfaExtension="http://www.aiim.org/pdfa/ns/extension/"
                      xmlns:pdfaSchema="http://www.aiim.org/pdfa/ns/schema#"
                      xmlns:pdfaProperty="http://www.aiim.org/pdfa/ns/property#">
                   <pdfaExtension:schemas>
                    <rdf:Bag>
                     <rdf:li rdf:parseType="Resource">
                      <pdfaSchema:prefix>pdfaid</pdfaSchema:prefix>
                      <pdfaSchema:namespaceURI>http://www.aiim.org/pdfa/ns/id/</pdfaSchema:namespaceURI>
                      <pdfaSchema:schema>PDF/A identification schema</pdfaSchema:schema>
                      <pdfaSchema:property>
                       <rdf:Seq>
                        <rdf:li rdf:parseType="Resource">
                         <pdfaProperty:name>part</pdfaProperty:name>
                         <pdfaProperty:valueType>Integer</pdfaProperty:valueType>
                         <pdfaProperty:category>internal</pdfaProperty:category>
                         <pdfaProperty:description>PDF/A part</pdfaProperty:description>
                        </rdf:li>
                       </rdf:Seq>
                      </pdfaSchema:property>
                     </rdf:li>
                    </rdf:Bag>
                   </pdfaExtension:schemas>
                  </rdf:Description>
                  <rdf:Description rdf:about="" xmlns:pdfx="http://ns.adobe.com/pdfx/1.3/">
                   <pdfx:GTS_PDFXVersion>PDF/X-4</pdfx:GTS_PDFXVersion>
                  </rdf:Description>
                  <rdf:Description rdf:about="" xmlns:pdfuaid="http://www.aiim.org/pdfua/ns/id/">
                   <pdfuaid:part>2</pdfuaid:part>
                   <pdfuaid:rev>2024</pdfuaid:rev>
                  </rdf:Description>
                 </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>
                """.formatted(title);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = value.indexOf(needle); index >= 0;
                index = value.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
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
