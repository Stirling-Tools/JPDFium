package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.ColorBindings;
import stirling.software.jpdfium.panama.ImageObjBindings;
import stirling.software.jpdfium.panama.PageEditBindings;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** End-to-end coverage for the DeviceCMYK colour pipeline:. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfColorPipelineTest {

    private static final int PDF_COLORSPACE_DEVICECMYK = 3;
    private static final int PAGEOBJ_IMAGE = 3;
    private static final long META_COLORSPACE_OFFSET = ImageObjBindings.IMAGE_METADATA_LAYOUT
            .byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("colorspace"));

    private static Path resource(String path) throws Exception {
        URL url = PdfColorPipelineTest.class.getResource(path);
        assertNotNull(url, path + " test resource missing");
        return Path.of(url.toURI());
    }

    private static Path temp(String suffix) throws Exception {
        Path p = Files.createTempFile("jpdfium-color-", suffix);
        p.toFile().deleteOnExit();
        return p;
    }

    /** A synthetic but structurally valid ICC header declaring 4 components. */
    private static byte[] syntheticCmykProfile() {
        byte[] icc = new byte[132];
        icc[0] = 0;
        icc[1] = 0;
        icc[2] = 0;
        icc[3] = (byte) icc.length;
        System.arraycopy("CMYK".getBytes(StandardCharsets.US_ASCII), 0, icc, 16, 4);
        System.arraycopy("acsp".getBytes(StandardCharsets.US_ASCII), 0, icc, 36, 4);
        return icc;
    }

    /** First real CMYK profile on this machine, if any (used for the JDK ICC path). */
    private static Optional<byte[]> findCmykProfile() {
        String explicit = System.getProperty("jpdfium.test.cmykIcc");
        String[] candidates = {
            explicit,
            "/System/Library/ColorSync/Profiles/Generic CMYK Profile.icc",
            "/usr/share/color/icc/ghostscript/default_cmyk.icc",
            "/usr/share/color/icc/colord/SwappedRedAndGreen.icc",
        };
        for (String candidate : candidates) {
            if (candidate == null) continue;
            try {
                Path p = Path.of(candidate);
                if (Files.isReadable(p)) {
                    return Optional.of(Files.readAllBytes(p));
                }
            } catch (Exception _) {
                // try the next candidate
            }
        }
        return Optional.empty();
    }

    /** Metadata colourspace of the first image object on page 0, or -1 when absent. */
    private static int firstImageColorspace(PdfDocument doc) {
        try (PdfPage page = doc.page(0);
                Arena arena = Arena.ofConfined()) {
            MemorySegment rawPage = page.rawHandleForRead();
            int count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
            MemorySegment meta = arena.allocate(ImageObjBindings.IMAGE_METADATA_LAYOUT);
            for (int i = 0; i < count; i++) {
                MemorySegment obj =
                        (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                if (obj.equals(MemorySegment.NULL)) continue;
                int type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
                if (type != PAGEOBJ_IMAGE) continue;
                int ok = (int)
                        ImageObjBindings.FPDFImageObj_GetImageMetadata.invokeExact(obj, rawPage, meta);
                if (ok != 0) {
                    return meta.get(JAVA_INT, META_COLORSPACE_OFFSET);
                }
            }
        } catch (Throwable t) {
            throw new AssertionError("image metadata probe failed", t);
        }
        return -1;
    }

    @Test
    void capabilityFlagsMatchAvailabilityProbes() {
        int caps = ColorBindings.capabilities();
        assertEquals(0, caps & ~(ColorBindings.CAP_CMYK_VECTOR
                        | ColorBindings.CAP_CMYK_IMAGE
                        | ColorBindings.CAP_OUTPUT_INTENT),
                "unknown capability bits must not be set: 0x" + Integer.toHexString(caps));
        assertEquals(ColorBindings.has(ColorBindings.CAP_CMYK_VECTOR),
                ColorBindings.vectorCmykAvailable());
        assertEquals(ColorBindings.has(ColorBindings.CAP_CMYK_IMAGE),
                ColorBindings.imageConversionAvailable());
        assertEquals(ColorBindings.has(ColorBindings.CAP_OUTPUT_INTENT),
                ColorBindings.outputIntentAvailable());
        assertEquals(ColorBindings.vectorCmykAvailable(),
                PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK));
    }

    @Test
    void cmykJpegEncoderProducesValidJpeg() {
        assumeTrue(ColorBindings.imageConversionAvailable(),
                "CMYK JPEG encoder unavailable (Rust not built)");

        byte[] rgb = new byte[4 * 4 * 3];
        for (int i = 0; i < rgb.length; i += 3) {
            rgb[i] = (byte) 200;
            rgb[i + 1] = (byte) 40;
            rgb[i + 2] = (byte) 10;
        }
        byte[] jpeg = ColorBindings.samplesToCmykJpeg(rgb, 4, 4, 3, 90, 0);
        assertNotNull(jpeg, "CMYK JPEG encoding must succeed");
        assertTrue(jpeg.length > 4, "encoded JPEG must not be empty");
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        assertEquals((byte) 0xFF, jpeg[jpeg.length - 2]);
        assertEquals((byte) 0xD9, jpeg[jpeg.length - 1]);
    }

    @Test
    void convertImagesToCmykRewritesEmbeddedImages() throws Exception {
        assumeTrue(PdfColorConverter.supportsImageConversion(),
                "CMYK image conversion unavailable");

        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(200, 40, 10));
            g.fillRect(0, 0, 32, 32);
        } finally {
            g.dispose();
        }

        Path out = temp(".pdf");
        int converted;
        int pages;
        try (PdfDocument doc = PdfDocument.fromImage(img)) {
            pages = doc.pageCount();
            converted = PdfColorConverter.convertImagesToCmyk(doc, 90);
            doc.save(out);
        }
        assertTrue(converted >= 1,
                "image PDF must convert at least one image to DeviceCMYK, got " + converted);

        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(pages, reopened.pageCount(), "document must remain valid");
            assertEquals(PDF_COLORSPACE_DEVICECMYK, firstImageColorspace(reopened),
                    "converted image XObject must be DeviceCMYK");
        }
    }

    @Test
    void outputIntentInjectsCatalogEntry() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "OutputIntent support unavailable");

        byte[] pdf = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        int expectedPages;
        try (PdfDocument source = PdfDocument.open(pdf)) {
            expectedPages = source.pageCount();
        }
        byte[] out = PdfOutputIntent.apply(pdf, syntheticCmykProfile(),
                PdfOutputIntent.Spec.pdfx("JPDFium Test CMYK", "JPDFium pipeline test"));
        assertNotNull(out, "OutputIntent injection must produce bytes");

        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("/OutputIntents"), "catalog must gain /OutputIntents");
        assertTrue(text.contains("/GTS_PDFX"), "output intent must carry the PDF/X subtype");
        assertTrue(text.contains("/DestOutputProfile"), "output intent must embed the ICC stream");

        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(expectedPages, doc.pageCount(),
                    "document with output intent must still open with every page intact");
        }
    }

    @Test
    void recolorModesRewriteVectorColors() throws Exception {
        Path src = resource("/pdfs/general/minimal.pdf");
        int expectedPages;
        try (PdfDocument source = PdfDocument.open(src)) {
            expectedPages = source.pageCount();
        }

        try (PdfDocument doc = PdfDocument.open(src)) {
            assertTrue(PdfRecolor.invert(doc) > 0,
                    "invert must rewrite at least one fill/stroke");
        }
        try (PdfDocument doc = PdfDocument.open(src)) {
            assertTrue(PdfRecolor.tint(doc, 255, 0, 0, 100) > 0,
                    "a full-strength tint must rewrite at least one fill/stroke");
        }
        try (PdfDocument doc = PdfDocument.open(src)) {
            assertTrue(PdfRecolor.highContrast(doc, 2.0) > 0,
                    "high contrast must rewrite at least one fill/stroke");
        }
        try (PdfDocument doc = PdfDocument.open(src)) {
            // This fixture carries no colour operators, so every glyph is already black.
            assertEquals(0, PdfRecolor.binarize(doc, 128));
        }

        Path out = temp(".pdf");
        try (PdfDocument doc = PdfDocument.open(src)) {
            PdfRecolor.tint(doc, 0, 0, 255, 60);
            doc.save(out);
        }
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(expectedPages, reopened.pageCount(),
                    "recoloured document must remain valid with every page intact");
        }
    }

    @Test
    void iccCmykTransformMapsRgbToInkLevels() {
        byte[] profile = findCmykProfile().orElse(null);
        assumeTrue(profile != null, "no CMYK ICC profile available on this machine");

        IccCmykTransform transform = new IccCmykTransform(profile);
        int[] out = new int[4];

        transform.toCmyk(255, 255, 255, out);
        assertTrue(out[3] <= 32,
                "white must carry (almost) no black ink, K=" + out[3]);

        transform.toCmyk(0, 0, 0, out);
        assertTrue(out[3] >= 200,
                "black must be dominated by black ink, K=" + out[3]);

        for (int component : out) {
            assertTrue(component >= 0 && component <= 255,
                    "ink levels must be 8-bit: " + component);
        }
    }

    @Test
    void iccTransformRejectsNonCmykProfile() {
        byte[] srgb = java.awt.color.ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        JPDFiumException ex = assertThrows(JPDFiumException.class,
                () -> new IccCmykTransform(srgb));
        assertTrue(ex.getMessage().contains("CMYK"), ex.getMessage());
    }

    @Test
    void outputIntentRejectsProfileWithoutAcspSignature() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "OutputIntent support unavailable");

        byte[] pdf = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        byte[] malformed = syntheticCmykProfile();
        malformed[36] = 0;  // destroy the mandatory "acsp" signature
        assertThrows(JPDFiumException.class,
                () -> PdfOutputIntent.apply(pdf, malformed, PdfOutputIntent.Spec.pdfx("x", "y")));
    }
}
