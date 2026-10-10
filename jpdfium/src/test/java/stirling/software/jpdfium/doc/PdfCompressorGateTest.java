package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.Watermark;
import stirling.software.jpdfium.WatermarkApplier;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the compression gates:
 * signed PDFs are returned byte-identical, the XMP schema description is not
 * mistaken for a PDF/A claim, and no output ever exceeds its input.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfCompressorGateTest {

    private static Path resource(String path) throws Exception {
        URL url = PdfCompressorGateTest.class.getResource(path);
        assertNotNull(url, path + " test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void signedPdfIsReturnedByteIdentical() throws Exception {
        Path src = resource("/pdfs/general/irs_f1040.pdf");
        byte[] original = Files.readAllBytes(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            assertArrayEquals(original, out.bytes(),
                    "a signed PDF must be returned byte-for-byte unchanged");
            assertEquals(0, out.result().actions().size(),
                    "no compression actions may be applied to a signed PDF");
            assertFalse(out.result().warnings().isEmpty(),
                    "skipping a signed PDF must be reported as a warning");
            assertTrue(out.result().warnings().stream().anyMatch(w -> w.contains("signature")),
                    "the warning must explain the signature: " + out.result().warnings());
        }
    }

    @Test
    void xmpSchemaDescriptionIsNotTreatedAsPdfA() throws Exception {
        // This file embeds the generic pdfaid XMP schema description but makes
        // no conformance claim, so it must still be compressed.
        Path src = resource("/pdfs/general/pdfjs_alphatrans.pdf");
        long inSize = Files.size(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            assertTrue(out.bytes().length < inSize, "the file should still be compressed");
            assertTrue(out.result().warnings().stream().noneMatch(w -> w.contains("PDF/A")),
                    "must not report a PDF/A claim: " + out.result().warnings());
        }
    }

    @Test
    void unsignedPdfIsCompressedAndNeverInflated() throws Exception {
        Path src = resource("/pdfs/general/all_form_fields.pdf");
        long inSize = Files.size(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            assertTrue(out.bytes().length <= inSize, "output must never exceed the input");
            assertFalse(out.result().actions().isEmpty(), "an unsigned PDF should be compressed");
        }
    }

    @Test
    void compactBranchNeverInflates() throws Exception {
        Path src = resource("/pdfs/general/basic-text.pdf");
        long inSize = Files.size(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .optimizeStreams(false)
                            .removeUnusedObjects(true)
                            .build());
            assertTrue(out.bytes().length <= inSize,
                    "compact-only branch must never exceed the input");
        }
    }

    @Test
    void exactModeDisablesImagePassAndMetadataRemoval() throws Exception {
        Path src = resource("/pdfs/general/pdfjs_alphatrans.pdf");
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(doc,
                    CompressOptions.builder()
                            .preset(CompressPreset.MAXIMUM)
                            .preservationMode(PreservationMode.EXACT)
                            .build());
            assertEquals(0, out.result().imagesOptimized(),
                    "EXACT must not run the lossy image pass");
            assertEquals(0, out.result().metadataFieldsRemoved(),
                    "EXACT must not remove metadata");
            assertTrue(out.result().actions().stream().noneMatch(a -> a.startsWith("Native:")),
                    "no native image action under EXACT: " + out.result().actions());
        }
    }

    @Test
    void editedDocumentIsNotReplacedByTheOpenTimeBytes() throws Exception {
        // Open, edit (watermark), compress: the watermark must survive even when
        // the watermarked serialization is larger than the opened file.
        Path src = resource("/pdfs/general/minimal.pdf");
        byte[] original = Files.readAllBytes(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            WatermarkApplier.apply(doc, Watermark.text("CONFIDENTIAL").build());
            assertTrue(doc.isContentModified(),
                    "applying a watermark must flag the document as modified");
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            assertFalse(Arrays.equals(original, out.bytes()),
                    "compressing an edited document must not return the open-time bytes");
        }
    }

    @Test
    void editedSignedDocumentIsSerializedInsteadOfReturnedVerbatim() throws Exception {
        Path src = resource("/pdfs/general/irs_f1040.pdf");
        byte[] original = Files.readAllBytes(src);
        try (PdfDocument doc = PdfDocument.open(src)) {
            WatermarkApplier.apply(doc, Watermark.text("CONFIDENTIAL").build());
            assertTrue(doc.isContentModified(),
                    "applying a watermark must flag the signed document as modified");
            PdfCompressor.CompressResultWithBytes out = PdfCompressor.compress(
                    doc, CompressOptions.builder().preset(CompressPreset.LOSSLESS).build());
            assertFalse(Arrays.equals(original, out.bytes()),
                    "an edited signed document must be serialized, not returned verbatim");
            assertTrue(
                    out.result().warnings().stream()
                            .anyMatch(w -> w.contains("modified after it was opened")),
                    "the signature warning must explain the document was edited: "
                            + out.result().warnings());
        }
    }
}
