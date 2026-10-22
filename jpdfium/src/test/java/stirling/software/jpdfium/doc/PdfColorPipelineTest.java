package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.ImageIO;

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

/** DeviceCMYK colour pipeline integration tests. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfColorPipelineTest {

    private static final int PDF_COLORSPACE_DEVICECMYK = 3;
    private static final int PAGEOBJ_IMAGE = 3;
    private static final long META_COLORSPACE_OFFSET = ImageObjBindings.IMAGE_METADATA_LAYOUT
            .byteOffset(MemoryLayout.PathElement.groupElement("colorspace"));

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

    /** A header-only blob used to exercise the malformed-profile rejection path. */
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

    /** A structurally plausible profile whose only tag points outside the profile. */
    private static byte[] profileWithOutOfBoundsTag() {
        byte[] icc = new byte[200];
        ByteBuffer b = ByteBuffer.wrap(icc);
        b.putInt(0, icc.length);
        b.put(16, "CMYK".getBytes(StandardCharsets.US_ASCII));
        b.put(36, "acsp".getBytes(StandardCharsets.US_ASCII));
        b.putInt(128, 1);           // one tag
        b.putInt(132, 0x64657363);  // "desc"
        b.putInt(136, 500);         // offset past the declared profile
        b.putInt(140, 4);
        return icc;
    }

    /**
     * Minimal PDF with one form XObject containing a DeviceCMYK fill and a
     * DeviceRGB fill. Exercises form recursion, CMYK preservation and dirty
     * stream regeneration.
     */
    private static byte[] cmykFormPdf() throws Exception {
        byte[] formContent = ("0.1 0.2 0.3 0.4 k\n"
                + "10 10 80 80 re f\n"
                + "1 0 0 rg\n"
                + "100 10 80 80 re f\n"
                + "0.5 g\n"
                + "10 100 80 80 re f\n").getBytes(StandardCharsets.US_ASCII);
        byte[] pageContent = "q\n/Fm0 Do\nQ\n".getBytes(StandardCharsets.US_ASCII);
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] "
                + "/Resources << /XObject << /Fm0 4 0 R >> >> /Contents 5 0 R >>")
                .getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] "
                + "/Resources << >> /Length " + formContent.length + " >>\nstream\n"
                + new String(formContent, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Length " + pageContent.length + " >>\nstream\n"
                + new String(pageContent, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));

        return assemblePdf(objects);
    }

    /** A JPEG wrapped in Flate exercises PDFium's chained-filter decode path. */
    private static byte[] filteredImagePdf() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(255, 0, 0));
            g.fillRect(0, 0, 4, 4);
            g.setColor(new Color(0, 255, 0));
            g.fillRect(4, 0, 4, 4);
            g.setColor(new Color(0, 0, 255));
            g.fillRect(0, 4, 4, 4);
            g.setColor(new Color(255, 255, 0));
            g.fillRect(4, 4, 4, 4);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpeg", jpeg), "JPEG writer missing");

        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(deflated)) {
            z.write(jpeg.toByteArray());
        }
        byte[] imageData = deflated.toByteArray();
        byte[] pageContent = "q 100 0 0 100 0 0 cm /Im0 Do Q"
                .getBytes(StandardCharsets.US_ASCII);
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] "
                + "/Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>")
                .getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        image.write(("<< /Type /XObject /Subtype /Image /Width 8 /Height 8 "
                + "/ColorSpace /DeviceRGB /BitsPerComponent 8 "
                + "/Filter [/FlateDecode /DCTDecode] /Length " + imageData.length + " >>\nstream\n")
                .getBytes(StandardCharsets.US_ASCII));
        image.write(imageData);
        image.write("\nendstream".getBytes(StandardCharsets.US_ASCII));
        objects.add(image.toByteArray());
        objects.add(("<< /Length " + pageContent.length + " >>\nstream\n"
                + new String(pageContent, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));
        return assemblePdf(objects);
    }

    /** Minimal PDF containing only a DeviceGray JPEG image. */
    private static byte[] grayImagePdf() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.GRAY);
            g.fillRect(0, 0, 8, 8);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpeg", jpeg), "JPEG writer missing");
        byte[] imageData = jpeg.toByteArray();
        byte[] pageContent = "q 100 0 0 100 0 0 cm /Im0 Do Q"
                .getBytes(StandardCharsets.US_ASCII);
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] "
                + "/Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>")
                .getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        image.write(("<< /Type /XObject /Subtype /Image /Width 8 /Height 8 "
                + "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode "
                + "/Length " + imageData.length + " >>\nstream\n")
                .getBytes(StandardCharsets.US_ASCII));
        image.write(imageData);
        image.write("\nendstream".getBytes(StandardCharsets.US_ASCII));
        objects.add(image.toByteArray());
        objects.add(("<< /Length " + pageContent.length + " >>\nstream\n"
                + new String(pageContent, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));
        return assemblePdf(objects);
    }

    /** A form XObject containing a DeviceRGB JPEG image. */
    private static byte[] imageFormPdf() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(10, 120, 240));
            g.fillRect(0, 0, 8, 8);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpeg", jpeg), "JPEG writer missing");
        byte[] imageData = jpeg.toByteArray();
        byte[] formContent = "q 100 0 0 100 0 0 cm /Im0 Do Q"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] pageContent = "q /Fm0 Do Q".getBytes(StandardCharsets.US_ASCII);
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] "
                + "/Resources << /XObject << /Fm0 4 0 R >> >> /Contents 5 0 R >>")
                .getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /XObject /Subtype /Form /BBox [0 0 100 100] "
                + "/Resources << /XObject << /Im0 6 0 R >> >> /Length "
                + formContent.length + " >>\nstream\n"
                + new String(formContent, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Length " + pageContent.length + " >>\nstream\n"
                + new String(pageContent, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        image.write(("<< /Type /XObject /Subtype /Image /Width 8 /Height 8 "
                + "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode "
                + "/Length " + imageData.length + " >>\nstream\n")
                .getBytes(StandardCharsets.US_ASCII));
        image.write(imageData);
        image.write("\nendstream".getBytes(StandardCharsets.US_ASCII));
        objects.add(image.toByteArray());
        return assemblePdf(objects);
    }

    /** Colour space of the first image inside the first image-bearing form. */
    private static int firstImageColorspaceInForm(PdfDocument doc) {
        try (PdfPage page = doc.page(0); Arena arena = Arena.ofConfined()) {
            MemorySegment rawPage = page.rawHandleForRead();
            int count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
            MemorySegment meta = arena.allocate(ImageObjBindings.IMAGE_METADATA_LAYOUT);
            for (int i = 0; i < count; i++) {
                MemorySegment form = (MemorySegment) PageEditBindings.FPDFPage_GetObject
                        .invokeExact(rawPage, i);
                if (form.equals(MemorySegment.NULL)
                        || (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(form) != 5) {
                    continue;
                }
                int children = (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(form);
                for (int j = 0; j < children; j++) {
                    MemorySegment child = PageEditBindings.formObject(form, j);
                    if (child.equals(MemorySegment.NULL)
                            || (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(child) != 3) {
                        continue;
                    }
                    int ok = (int) ImageObjBindings.FPDFImageObj_GetImageMetadata
                            .invokeExact(child, rawPage, meta);
                    if (ok != 0) return meta.get(JAVA_INT, META_COLORSPACE_OFFSET);
                }
            }
        } catch (Throwable t) {
            throw new AssertionError("form image metadata probe failed", t);
        }
        return -1;
    }

    /** A page painted with a Separation colour; CMYK conversion must leave it alone. */
    private static byte[] spotColorPdf() throws Exception {
        byte[] content = "/CS0 cs 0.5 scn\n10 10 80 80 re f\n"
                .getBytes(StandardCharsets.US_ASCII);
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] "
                + "/Resources << /ColorSpace << /CS0 [/Separation /Spot /DeviceCMYK 5 0 R] >> >> "
                + "/Contents 4 0 R >>").getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Length " + content.length + " >>\nstream\n"
                + new String(content, StandardCharsets.US_ASCII) + "endstream")
                .getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0 0] /C1 [0 1 1 0] /N 1 >>"
                .getBytes(StandardCharsets.US_ASCII));
        return assemblePdf(objects);
    }

    private static byte[] assemblePdf(List<byte[]> objects) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        int[] offsets = new int[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            out.write((i + 1 + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
            out.write(objects.get(i));
            out.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        }
        int xref = out.size();
        out.write(("xref\n0 " + (objects.size() + 1) + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write("0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int i = 1; i < offsets.length; i++) {
            out.write((String.format("%010d", offsets[i]) + " 00000 n \n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    /** First real CMYK profile on this machine, if any (used for the JDK ICC path). */
    private static Optional<byte[]> findCmykProfile() {
        String explicit = System.getProperty("jpdfium.test.cmykIcc");
        if (explicit != null && !explicit.isBlank()) {
            try {
                byte[] bytes = Files.readAllBytes(Path.of(explicit));
                if (ICC_Profile.getInstance(bytes).getNumComponents() != 4) {
                    throw new JPDFiumException("configured CMYK profile is not 4-component: "
                            + explicit);
                }
                return Optional.of(bytes);
            } catch (IOException e) {
                throw new JPDFiumException("cannot read configured CMYK profile: " + explicit, e);
            }
        }
        String[] candidates = {
            "/System/Library/ColorSync/Profiles/Generic CMYK Profile.icc",
            "/usr/share/color/icc/ghostscript/default_cmyk.icc",
            "/usr/share/color/icc/ISOcoated_v2_300_eci.icc",
            "/usr/share/color/icc/colord/ISOcoated_v2_300_eci.icc",
            "/usr/share/color/icc/colord/SwappedRedAndGreen.icc",
        };
        for (String candidate : candidates) {
            if (candidate == null) continue;
            try {
                Path p = Path.of(candidate);
                if (!Files.isReadable(p)) continue;
                byte[] bytes = Files.readAllBytes(p);
                // Colord ships RGB profiles too; only accept a real 4-component
                // profile so the test cannot fail on a valid non-CMYK file.
                if (ICC_Profile.getInstance(bytes).getNumComponents() == 4) {
                    return Optional.of(bytes);
                }
            } catch (Exception _) {
                // try the next candidate
            }
        }
        return Optional.of(PdfIccProfiles.compactCmyk());
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
        assertEquals(ColorBindings.vectorCmykAvailable()
                        && ColorBindings.colorSpaceAvailable()
                        && ColorBindings.iccConversionAvailable(),
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
            converted = PdfColorConverter.convertImagesToCmyk(doc, 90, true);
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
    void convertsImageWithChainedFilters() throws Exception {
        assumeTrue(PdfColorConverter.supportsImageConversion(),
                "CMYK image conversion unavailable");

        Path out = temp(".pdf");
        int converted;
        try (PdfDocument doc = PdfDocument.open(filteredImagePdf())) {
            converted = PdfColorConverter.convertImagesToCmyk(doc, 90, true);
            doc.save(out);
        }
        assertTrue(converted >= 1, "chained-filter image must be converted");
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(PDF_COLORSPACE_DEVICECMYK, firstImageColorspace(reopened),
                    "chained-filter image must become DeviceCMYK");
        }
    }

    @Test
    void preservesDeviceGrayImages() throws Exception {
        assumeTrue(PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK),
                "vector CMYK unavailable");

        Path out = temp(".pdf");
        try (PdfDocument doc = PdfDocument.open(grayImagePdf())) {
            assertEquals(0, PdfColorConverter.toCmyk(doc),
                    "DeviceGray images are already K-only and must be preserved");
            doc.save(out);
        }
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(ColorBindings.COLORSPACE_DEVICEGRAY, firstImageColorspace(reopened),
                    "grayscale image must stay DeviceGray");
        }
    }

    @Test
    void convertsImageInsideForm() throws Exception {
        assumeTrue(PdfColorConverter.supportsImageConversion(),
                "CMYK image conversion unavailable");
        assumeTrue(PdfColorConverter.supportsFormConversion(),
                "form write-back unavailable");

        Path out = temp(".pdf");
        int converted;
        try (PdfDocument doc = PdfDocument.open(imageFormPdf())) {
            converted = PdfColorConverter.convertImagesToCmyk(doc, 90, true);
            doc.save(out);
        }
        assertTrue(converted >= 1, "image inside form must be converted");
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(PDF_COLORSPACE_DEVICECMYK, firstImageColorspaceInForm(reopened),
                    "form image must become DeviceCMYK");
        }
    }

    @Test
    void preservesSeparationColoursByDefault() throws Throwable {
        assumeTrue(PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK),
                "vector CMYK unavailable");

        Path out = temp(".pdf");
        try (PdfDocument doc = PdfDocument.open(spotColorPdf())) {
            assertEquals(0, PdfColorConverter.toCmyk(doc),
                    "Separation colours must not be flattened by default");
            doc.save(out);
        }
        try (PdfDocument reopened = PdfDocument.open(out);
                PdfPage page = reopened.page(0)) {
            MemorySegment obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject
                    .invokeExact(page.rawHandleForRead(), 0);
            assertEquals(ColorBindings.COLORSPACE_SEPARATION,
                    ColorBindings.fillColorSpace(obj),
                    "Separation fill must stay a Separation colour");
        }
    }

    @Test
    void cmykFormConversionPreservesCmykAndConvertsRgb() throws Throwable {
        assumeTrue(PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK),
                "vector CMYK unavailable");
        assumeTrue(PdfColorConverter.supportsFormConversion(),
                "form conversion requires the JPDFium PDFium patch");

        Path out = temp(".pdf");
        try (PdfDocument doc = PdfDocument.open(cmykFormPdf())) {
            assertEquals(1, PdfColorConverter.toCmyk(doc),
                    "the RGB child inside the form must be converted, the CMYK child skipped");
            doc.save(out);
        }

        try (PdfDocument doc = PdfDocument.open(out);
                PdfPage page = doc.page(0)) {
            MemorySegment rawPage = page.rawHandleForRead();
            MemorySegment form = (MemorySegment) PageEditBindings.FPDFPage_GetObject
                    .invokeExact(rawPage, 0);
            assertNotNull(form, "form object must survive save");
            int count = (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(form);
            assertEquals(3, count);

            int[] cmyk = new int[5];
            MemorySegment preserved = PageEditBindings.formObject(form, 0);
            assertTrue(ColorBindings.getFillColorCmyk(preserved, cmyk),
                    "original DeviceCMYK fill must still be DeviceCMYK");
            assertEquals(26, cmyk[0]);
            assertEquals(51, cmyk[1]);
            assertEquals(77, cmyk[2]);
            assertEquals(102, cmyk[3]);

            MemorySegment converted = PageEditBindings.formObject(form, 1);
            assertTrue(ColorBindings.getFillColorCmyk(converted, cmyk),
                    "RGB fill inside the form must become DeviceCMYK");
            assertEquals(0, cmyk[0]);
            assertEquals(255, cmyk[1]);
            assertEquals(255, cmyk[2]);
            assertEquals(0, cmyk[3]);

            MemorySegment preservedGray = PageEditBindings.formObject(form, 2);
            assertEquals(ColorBindings.COLORSPACE_DEVICEGRAY,
                    ColorBindings.fillColorSpace(preservedGray),
                    "DeviceGray content is already a valid PDF/X process colour");
        }
    }

    @Test
    void outputIntentInjectsCatalogEntry() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "OutputIntent support unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no real CMYK ICC profile available on this machine");

        byte[] pdf = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        int expectedPages;
        try (PdfDocument source = PdfDocument.open(pdf)) {
            expectedPages = source.pageCount();
        }
        byte[] out = PdfOutputIntent.apply(pdf, icc,
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
        assumeTrue(PdfRecolor.isSupported(), "colour-space probe unavailable");
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
            assertEquals(0, PdfRecolor.highContrast(doc, 2.0),
                    "black stays black under contrast stretching");
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
        byte[] profile = PdfIccProfiles.compactCmyk();

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
    void iccSourceProfileRoundTripsThroughTargetProfile() {
        byte[] profile = PdfIccProfiles.compactCmyk();

        IccCmykTransform transform = new IccCmykTransform(profile);
        int[] out = new int[4];
        transform.toCmykFromIcc(profile, new float[] {0f, 0f, 0f, 1f}, out);
        assertTrue(out[3] >= 120, "100% K source must keep substantial black ink: " + out[3]);

        IccCmykTransform.sourceToCmyk(profile, new float[] {0f, 0f, 0f, 0f}, true, out);
        assertTrue(out[3] <= 32, "white source must carry little black: " + out[3]);
    }

    @Test
    void iccTransformRejectsNonCmykProfile() {
        byte[] srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
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

    @Test
    void outputIntentRejectsProfileWithOutOfBoundsTag() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "OutputIntent support unavailable");

        byte[] pdf = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        assertThrows(JPDFiumException.class,
                () -> PdfOutputIntent.apply(pdf, profileWithOutOfBoundsTag(),
                        PdfOutputIntent.Spec.pdfx("x", "y")));
    }

    @Test
    void outputIntentRejectsProfileWithoutTagTable() throws Exception {
        assumeTrue(PdfOutputIntent.isSupported(), "OutputIntent support unavailable");

        byte[] pdf = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        byte[] headerOnly = syntheticCmykProfile();
        assertThrows(JPDFiumException.class,
                () -> PdfOutputIntent.apply(pdf, headerOnly, PdfOutputIntent.Spec.pdfx("x", "y")));
    }
}
