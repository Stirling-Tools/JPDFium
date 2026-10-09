package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageIO;
import stirling.software.jpdfium.doc.PdfColorConverter.ColorConvertOptions;
import stirling.software.jpdfium.doc.PdfColorConverter.ColorSpace;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression coverage for the binary-free paths: encryption, capability probes,
 * and the in-process image optimizer.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfBinaryFreeApiTest {

    private static Path resource(String path) throws Exception {
        URL url = PdfBinaryFreeApiTest.class.getResource(path);
        assertNotNull(url, path + " test resource missing");
        return Path.of(url.toURI());
    }

    private static Path temp(String suffix) throws Exception {
        Path p = Files.createTempFile("jpdfium-binfree-", suffix);
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    void encryptionRoundTripsWithoutQpdfBinary() throws Exception {
        assertTrue(PdfSecurity.isSupported(), "native qpdf encrypt/decrypt must be available");
        Path src = resource("/pdfs/general/minimal.pdf");
        Path encrypted = temp(".pdf");
        Path decrypted = temp(".pdf");

        PdfEncryption.encrypt(src, encrypted, "user123", "owner456", 256);
        assertTrue(Files.size(encrypted) > 0, "encrypted output must exist");

        try (PdfDocument doc = PdfDocument.open(encrypted, "user123")) {
            assertTrue(PdfEncryption.isEncrypted(doc.rawHandle()),
                    "output must be encrypted");
        }

        PdfEncryption.decrypt(encrypted, decrypted, "user123");
        try (PdfDocument doc = PdfDocument.open(decrypted)) {
            assertFalse(PdfEncryption.isEncrypted(doc.rawHandle()),
                    "decrypted output must not be encrypted");
        }
    }

    @Test
    void colorConverterCapabilityProbes() {
        assertTrue(PdfColorConverter.supportsColorSpace(ColorSpace.GRAYSCALE));
        assertTrue(PdfColorConverter.supportsColorSpace(ColorSpace.RGB));
        assertFalse(PdfColorConverter.supportsColorSpace(ColorSpace.CMYK),
                "CMYK is declared but not implemented natively");
        assertEquals(Set.of(ColorSpace.GRAYSCALE, ColorSpace.RGB),
                PdfColorConverter.supportedColorSpaces());
    }

    @Test
    void toRgbRunsOnRealDocument() throws Exception {
        Path src = resource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(src)) {
            assertTrue(PdfColorConverter.toRgb(doc) > 0,
                    "at least one object must be rewritten for a text page with preserveBlack(false)");
        }
    }

    @Test
    void cmykTargetIsRejected() throws Exception {
        Path src = resource("/pdfs/general/minimal.pdf");
        try (PdfDocument doc = PdfDocument.open(src)) {
            ColorConvertOptions opts = ColorConvertOptions.builder()
                    .targetColorSpace(ColorSpace.CMYK)
                    .build();
            assertThrows(JPDFiumException.class, () -> PdfColorConverter.convert(doc, opts));
        }
    }

    @Test
    void optimizerBackendProbe() {
        assertFalse(PdfOptimizer.isImageOptimizationSupported(),
                "qpdf writer path has no image optimization");
    }

    @Test
    void imageOptimizerDownsamplesEmbeddedImages() throws Exception {
        assumeTrue(PdfImageOptimizer.isSupported(), "image edit bindings unavailable");

        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.ORANGE);
            g.fillRect(0, 0, 400, 300);
        } finally {
            g.dispose();
        }
        Path png = temp(".png");
        PdfImageIO.write(img, "PNG", png);

        // Baseline colour of the un-optimized page (sampled at page centre).
        Path baseline = temp(".pdf");
        int baseRgb;
        try (PdfDocument doc = PdfDocument.fromImage(png)) {
            doc.save(baseline);
        }
        try (PdfDocument re = PdfDocument.open(baseline)) {
            BufferedImage r = re.renderImage(0);
            baseRgb = r.getRGB(r.getWidth() / 2, r.getHeight() / 2);
        }

        Path out = temp(".pdf");
        int pages;
        try (PdfDocument doc = PdfDocument.fromImage(png)) {
            pages = doc.pageCount();
            int rewritten = PdfImageOptimizer.optimize(doc, 1);
            assertTrue(rewritten >= 1,
                    "image PDF should have at least one downsampled image, got " + rewritten);
            doc.save(out);
        }
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(pages, reopened.pageCount(),
                    "optimized document must remain structurally valid");

            // The bitmap round-trip must not permute colour channels: the
            // optimized page must render the same colour as the un-optimized one.
            BufferedImage rendered = reopened.renderImage(0);
            int rgb = rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2);
            int dr = Math.abs(((baseRgb >> 16) & 0xFF) - ((rgb >> 16) & 0xFF));
            int dg = Math.abs(((baseRgb >> 8) & 0xFF) - ((rgb >> 8) & 0xFF));
            int db = Math.abs((baseRgb & 0xFF) - (rgb & 0xFF));
            assertTrue(dr <= 12 && dg <= 12 && db <= 12,
                    "optimized colour must match baseline (no channel swap): base="
                            + Integer.toHexString(baseRgb) + " optimized=" + Integer.toHexString(rgb));
        }
    }
}
