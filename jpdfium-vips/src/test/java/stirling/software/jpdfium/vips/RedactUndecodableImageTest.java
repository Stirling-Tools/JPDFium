package stirling.software.jpdfium.vips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.NativeRuntime;

/**
 * Images in encodings PDFium has no codec for (JPEG XL, HEIF, AVIF) are decoded
 * through libvips when a redaction box covers part of them, so only the box is
 * erased. Without that they would be dropped whole. Each fixture is a 100x100
 * image drawn at (100,100)-(300,300) with green, red, blue and yellow quarters;
 * the box covers the top-left quarter.
 */
class RedactUndecodableImageTest {

    private static final int BLACK = 0x000000;

    @ParameterizedTest
    @CsvSource({
        "quad.jxl,  JXLDecode,  JXL",
        "quad.heic, HEIFDecode, HEIC",
        "quad.avif, AVIFDecode, AVIF",
    })
    void partlyCoveredImageKeepsEverythingOutsideTheBox(String fixture, String filter, VipsFormat format)
            throws IOException {
        assumeTrue(NativeRuntime.isFull(), "image redaction needs the real PDFium natives");
        VipsAvailability.State s = VipsAvailability.probe();
        assumeTrue(s.available(), "libvips unavailable: " + VipsAvailability.installMessage(s));
        assumeTrue(VipsAvailability.isFormatDecodable(format), format + " decoding unavailable");

        byte[] encoded;
        try (InputStream in = getClass().getResourceAsStream("/images/redact-codecs/" + fixture)) {
            assertNotNull(in, fixture);
            encoded = in.readAllBytes();
        }
        byte[] output;
        try (PdfDocument doc = PdfDocument.open(imagePdf(filter, encoded))) {
            try (PdfPage page = doc.page(0)) {
                page.redactRegion(new Rect(100, 200, 100, 100), 0xFF000000, true);
            }
            output = doc.saveBytes();
        }

        String text = new String(output, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(new String(encoded, StandardCharsets.ISO_8859_1)),
                "the original " + format + " stream must be gone");
        try (PDDocument doc = Loader.loadPDF(output)) {
            PDResources resources = doc.getPage(0).getResources();
            List<PDImageXObject> images = new ArrayList<>();
            for (COSName name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof PDImageXObject image) images.add(image);
            }
            assertEquals(1, images.size(), "expected exactly one image on the page");
            PDImageXObject image = images.get(0);
            BufferedImage pixels = image.getImage();
            assertEquals(100, pixels.getWidth());
            assertQuarter(pixels, 0, 0, BLACK, 0, "covered top-left quarter");
            assertQuarter(pixels, 1, 0, 0xFF0000, 24, "top-right quarter");
            assertQuarter(pixels, 0, 1, 0x0000FF, 24, "bottom-left quarter");
            assertQuarter(pixels, 1, 1, 0xFFFF00, 24, "bottom-right quarter");
            assertNull(image.getCOSObject().getDictionaryObject(COSName.SMASK),
                    "an opaque image needs no soft mask");
        }
    }

    private static void assertQuarter(BufferedImage image, int qx, int qy, int expected, int tolerance,
                                      String what) {
        for (int y = qy * 50 + 6; y < (qy + 1) * 50 - 6; y += 3) {
            for (int x = qx * 50 + 6; x < (qx + 1) * 50 - 6; x += 3) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    if (Math.abs(((rgb >> shift) & 0xFF) - ((expected >> shift) & 0xFF)) > tolerance) {
                        fail(String.format("%s: pixel (%d,%d) = %06X, expected %06X (tolerance %d)",
                                what, x, y, rgb, expected, tolerance));
                    }
                }
            }
        }
    }

    /** One letter page drawing a 100x100 /DeviceRGB image stored with |filter|. */
    private static byte[] imagePdf(String filter, byte[] data) {
        byte[] content = latin1("q 200 0 0 200 100 100 cm /Im0 Do Q");
        List<byte[]> objects = List.of(
                latin1("<< /Type /Catalog /Pages 2 0 R >>"),
                latin1("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                latin1("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]"
                        + " /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>"),
                stream("", content),
                stream("/Type /XObject /Subtype /Image /Width 100 /Height 100 /ColorSpace /DeviceRGB"
                        + " /BitsPerComponent 8 /Filter /" + filter, data));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(latin1("%PDF-1.7\n"));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            out.writeBytes(latin1((i + 1) + " 0 obj\n"));
            out.writeBytes(objects.get(i));
            out.writeBytes(latin1("\nendobj\n"));
        }
        int xref = out.size();
        StringBuilder sb = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) sb.append(String.format("%010d 00000 n \n", offset));
        sb.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        out.writeBytes(latin1(sb.toString()));
        return out.toByteArray();
    }

    private static byte[] stream(String dict, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(latin1("<< " + dict + " /Length " + data.length + " >>\nstream\n"));
        out.writeBytes(data);
        out.writeBytes(latin1("\nendstream"));
        return out.toByteArray();
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
