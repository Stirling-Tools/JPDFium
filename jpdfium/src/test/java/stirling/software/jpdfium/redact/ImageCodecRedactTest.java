package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.SaveOptions;
import stirling.software.jpdfium.model.Rect;

/**
 * A partly covered image keeps its uncovered pixels in every encoding, and is
 * stored no larger than it has to be. Each fixture draws a 100x100 image at
 * (100,100)-(300,300) whose quarters are green, red, blue and yellow (or, for
 * 1-bit scans, white, black, white, white); the box covers the top-left quarter.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class ImageCodecRedactTest {

    private static final int GREEN = 0x00C800;
    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;
    private static final int YELLOW = 0xFFFF00;
    private static final int WHITE = 0xFFFFFF;
    private static final int BLACK = 0x000000;
    private static final int OPAQUE_BLACK = 0xFF000000;
    private static final Rect TOP_LEFT_QUARTER = new Rect(100, 200, 100, 100);
    private static final String RGB8 = "/ColorSpace /DeviceRGB /BitsPerComponent 8";

    @Test
    void flateRgbDropsNoPixelAndGainsNoSoftMask() throws Exception {
        Redacted r = redact(imagePdf(RGB8 + " /Filter /FlateDecode", deflate(quadrantRgb(100, 1)), null));
        r.assertQuarters(0, GREEN, RED, BLUE, YELLOW);
        r.assertEncoding("DeviceRGB", 8, false);
    }

    @Test
    void jpegKeepsItsUncoveredPixels() throws Exception {
        Redacted r = redact(imagePdf(RGB8 + " /Filter /DCTDecode", jpeg(), null));
        r.assertQuarters(40, GREEN, RED, BLUE, YELLOW);
        r.assertEncoding("DeviceRGB", 8, false);
    }

    @Test
    void cmykJpegKeepsItsUncoveredPixels() throws Exception {
        // PDFium shows the image as RGB, and the erase is stored that way: the
        // public API offers no way to write CMYK back.
        Redacted r = redact(imagePdf("/ColorSpace /DeviceCMYK /BitsPerComponent 8 /Filter /DCTDecode"
                + " /Decode [1 0 1 0 1 0 1 0]", resource("quad-cmyk.jpg"), null));
        r.assertQuarters(60, GREEN, RED, 0x2E3092, YELLOW);
        r.assertEncoding("DeviceRGB", 8, false);
    }

    @Test
    void jpeg2000KeepsItsUncoveredPixels() throws Exception {
        Redacted r = redact(imagePdf("/BitsPerComponent 8 /Filter /JPXDecode", resource("quad.jp2"), null));
        r.assertQuarters(0, GREEN, RED, BLUE, YELLOW);
        r.assertEncoding("DeviceRGB", 8, false);
    }

    @Test
    void jbig2ScanStaysOneBit() throws Exception {
        Redacted r = redact(imagePdf("/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /JBIG2Decode",
                resource("bilevel.jbig2"), null));
        r.assertQuarters(0, WHITE, BLACK, WHITE, WHITE);
        r.assertEncoding("DeviceGray", 1, false);
    }

    @Test
    void ccittScanStaysOneBit() throws Exception {
        Redacted r = redact(imagePdf("/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /CCITTFaxDecode"
                + " /DecodeParms << /K -1 /Columns 100 /Rows 100 /BlackIs1 true >>",
                resource("bilevel.g4"), null));
        r.assertQuarters(0, WHITE, BLACK, WHITE, WHITE);
        r.assertEncoding("DeviceGray", 1, false);
    }

    @Test
    void outputBudgetStillHoldsWhenTheSaveNeedsGrayCompaction(@TempDir Path dir) throws Exception {
        // An erased gray image sends a file save through the file-backed
        // compaction stage; the budget must stop it there too, leaving no file.
        Path dest = dir.resolve("out.pdf");
        try (PdfDocument doc = PdfDocument.open(imagePdf("/ColorSpace /DeviceGray /BitsPerComponent 1"
                + " /Filter /JBIG2Decode", resource("bilevel.jbig2"), null))) {
            try (PdfPage page = doc.page(0)) {
                page.redactRegion(TOP_LEFT_QUARTER, OPAQUE_BLACK, true);
            }
            assertThrows(JPDFiumException.class, () -> doc.saveTo(dest, SaveOptions.maxOutputBytes(1024)));
            assertFalse(Files.exists(dest), "a refused save must not leave a file");
            doc.saveTo(dest, SaveOptions.fast());
        }
        assertTrue(Files.size(dest) > 1024);
    }

    @Test
    void grayImageStaysGrayOnFileSave(@TempDir Path dir) throws Exception {
        Path dest = dir.resolve("out.pdf");
        byte[] input = imagePdf("/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",
                deflate(grayQuadrants()), null);
        try (PdfDocument doc = PdfDocument.open(input)) {
            try (PdfPage page = doc.page(0)) {
                page.redactRegion(TOP_LEFT_QUARTER, OPAQUE_BLACK, true);
            }
            doc.saveTo(dest, SaveOptions.fast());
        }
        byte[] output = Files.readAllBytes(dest);
        assertFalse(containsStream(output, imageStream(input)), "the original image data must be gone");
        List<Redacted> images = drawnImages(output);
        assertEquals(1, images.size(), "expected exactly one image drawn on the page");
        images.get(0).assertQuarters(0, 0x404040, 0x808080, 0xC0C0C0, 0xC0C0C0);
        images.get(0).assertEncoding("DeviceGray", 8, false);
    }

    @Test
    void grayImageStaysGray() throws Exception {
        Redacted r = redact(imagePdf("/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",
                deflate(grayQuadrants()), null));
        r.assertQuarters(0, 0x404040, 0x808080, 0xC0C0C0, 0xC0C0C0);
        r.assertEncoding("DeviceGray", 8, false);
    }

    /** Gray samples whose quarters are 0x40, 0x80, 0xC0 and 0xC0. */
    private static byte[] grayQuadrants() {
        byte[] gray = new byte[100 * 100];
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                gray[y * 100 + x] = (byte) (y < 50 ? (x < 50 ? 0x40 : 0x80) : 0xC0);
            }
        }
        return gray;
    }

    @Test
    void sixteenBitImageKeepsItsUncoveredPixels() throws Exception {
        // Stored at 8 bits: PDFium decodes to 8 bits and writes nothing deeper.
        Redacted r = redact(imagePdf("/ColorSpace /DeviceRGB /BitsPerComponent 16 /Filter /FlateDecode",
                deflate(quadrantRgb(100, 2)), null));
        r.assertQuarters(0, GREEN, RED, BLUE, YELLOW);
        r.assertEncoding("DeviceRGB", 8, false);
    }

    @Test
    void softMaskSurvives() throws Exception {
        byte[] alpha = new byte[100 * 100];
        Arrays.fill(alpha, (byte) 0xFF);
        Arrays.fill(alpha, 99 * 100, 100 * 100, (byte) 0x80);  // last row half transparent
        Redacted r = redact(imagePdf(RGB8 + " /Filter /FlateDecode", deflate(quadrantRgb(100, 1)),
                deflate(alpha)));
        r.assertQuarters(0, GREEN, RED, BLUE, YELLOW);
        r.assertEncoding("DeviceRGB", 8, true);
    }

    @Test
    void indexedImageKeepsItsUncoveredPixels() throws Exception {
        byte[] indexes = new byte[100 * 100];
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                indexes[y * 100 + x] = (byte) ((y < 50 ? 0 : 2) + (x < 50 ? 0 : 1));
            }
        }
        Redacted r = redact(imagePdf("/ColorSpace [/Indexed /DeviceRGB 3 <00C800FF00000000FFFFFF00>]"
                + " /BitsPerComponent 8 /Filter /FlateDecode", deflate(indexes), null));
        r.assertQuarters(0, GREEN, RED, BLUE, YELLOW);
    }

    @Test
    void inlineImageKeepsItsUncoveredPixels() throws Exception {
        byte[] data = deflate(quadrantRgb(50, 1));
        byte[] inline = concat(latin1("BI /W 50 /H 50 /CS /RGB /BPC 8 /F /Fl ID "), data, latin1(" EI"));
        Redacted r = redact(pdf(concat(latin1("q 200 0 0 200 100 100 cm "), inline, latin1(" Q")),
                null, null, null), data, 50);
        r.assertQuarters(0, GREEN, RED, BLUE, YELLOW);
    }

    @Test
    void jpegXlWithoutADecoderIsNeverKeptUnderTheBox() throws Exception {
        // No image codec on this classpath reads JPEG XL: the image fails closed.
        byte[] jxl = resource("quad.jxl");
        byte[] output = redactBytes(imagePdf(RGB8 + " /Filter /JXLDecode", jxl, null));
        assertFalse(containsStream(output, jxl), "the original JPEG XL stream must be gone");
        for (Redacted image : drawnImages(output)) {
            assertQuarter(image.image(), 0, 0, BLACK, 0, "covered quarter");
        }
    }

    // ---- fixtures ------------------------------------------------------------

    /** Quarters green, red, blue, yellow; |bytesPerSample| 2 writes 16-bit samples. */
    private static byte[] quadrantRgb(int size, int bytesPerSample) {
        int[] colors = {GREEN, RED, BLUE, YELLOW};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = colors[(y < size / 2 ? 0 : 2) + (x < size / 2 ? 0 : 1)];
                for (int shift = 16; shift >= 0; shift -= 8) {
                    int c = (rgb >> shift) & 0xFF;
                    for (int b = 0; b < bytesPerSample; b++) out.write(c);  // c * 257 when 16-bit
                }
            }
        }
        return out.toByteArray();
    }

    private static byte[] jpeg() throws IOException {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        int[] colors = {GREEN, RED, BLUE, YELLOW};
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                image.setRGB(x, y, colors[(y < 50 ? 0 : 2) + (x < 50 ? 0 : 1)]);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = ImageCodecRedactTest.class.getResourceAsStream(
                "/images/redact-codecs/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    private static byte[] imagePdf(String imageDict, byte[] data, byte[] softMask) {
        return pdf(latin1("q 200 0 0 200 100 100 cm /Im0 Do Q"), imageDict, data, softMask);
    }

    /** One letter page; an image XObject /Im0 when |imageDict| is not null. */
    private static byte[] pdf(byte[] content, String imageDict, byte[] data, byte[] softMask) {
        List<byte[]> objects = new ArrayList<>();
        objects.add(latin1("<< /Type /Catalog /Pages 2 0 R >>"));
        objects.add(latin1("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"));
        objects.add(latin1("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources "
                + (imageDict == null ? "<< >>" : "<< /XObject << /Im0 5 0 R >> >>")
                + " /Contents 4 0 R >>"));
        objects.add(stream("", content));
        if (imageDict != null) {
            objects.add(stream("/Type /XObject /Subtype /Image /Width 100 /Height 100 " + imageDict
                    + (softMask == null ? "" : " /SMask 6 0 R"), data));
            if (softMask != null) {
                objects.add(stream("/Type /XObject /Subtype /Image /Width 100 /Height 100"
                        + " /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", softMask));
            }
        }
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
        return concat(latin1("<< " + dict + " /Length " + data.length + " >>\nstream\n"), data,
                latin1("\nendstream"));
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    // ---- redaction and checks ------------------------------------------------

    private static byte[] redactBytes(byte[] input) {
        try (PdfDocument doc = PdfDocument.open(input)) {
            try (PdfPage page = doc.page(0)) {
                page.redactRegion(TOP_LEFT_QUARTER, OPAQUE_BLACK, true);
            }
            return doc.saveBytes();
        }
    }

    private static Redacted redact(byte[] input) throws IOException {
        return redact(input, imageStream(input), 100);
    }

    /** |size|: the image's width and height, which the erase must keep. */
    private static Redacted redact(byte[] input, byte[] original, int size) throws IOException {
        byte[] output = redactBytes(input);
        assertFalse(containsStream(output, original), "the original image data must be gone");
        List<Redacted> images = drawnImages(output);
        assertEquals(1, images.size(), "expected exactly one image drawn on the page");
        Redacted image = images.get(0);
        assertEquals(size, image.image().getWidth(), "image width");
        assertEquals(size, image.image().getHeight(), "image height");
        return image;
    }

    /** A drawn image, decoded, with the dictionary facts the checks need. */
    private record Redacted(BufferedImage image, COSBase colorSpace, int bitsPerComponent,
                            boolean softMask) {

        static Redacted of(PDImage image) throws IOException {
            COSDictionary dict = image.getCOSObject();
            return new Redacted(image.getImage(), dict.getDictionaryObject(COSName.COLORSPACE, COSName.CS),
                    dict.getInt(COSName.BITS_PER_COMPONENT, COSName.BPC, -1),
                    dict.getDictionaryObject(COSName.SMASK) != null);
        }

        /** The covered quarter is exactly black; the others keep |tolerance| of the source. */
        void assertQuarters(int tolerance, int topLeft, int topRight, int bottomLeft, int bottomRight) {
            assertQuarter(image, 0, 0, BLACK, 0, "covered top-left quarter");
            assertQuarter(image, 1, 0, topRight, tolerance, "top-right quarter");
            assertQuarter(image, 0, 1, bottomLeft, tolerance, "bottom-left quarter");
            assertQuarter(image, 1, 1, bottomRight, tolerance, "bottom-right quarter");
        }

        void assertEncoding(String expectedColorSpace, int expectedBits, boolean expectSoftMask) {
            assertEquals(COSName.getPDFName(expectedColorSpace), colorSpace, "color space");
            assertEquals(expectedBits, bitsPerComponent, "bits per component");
            assertEquals(expectSoftMask, softMask,
                    expectSoftMask ? "the soft mask must survive" : "an opaque image needs no soft mask");
        }
    }

    /** Checks quarter (qx, qy) away from its edges, where lossy codecs ring. */
    private static void assertQuarter(BufferedImage image, int qx, int qy, int expected, int tolerance,
                                      String what) {
        int half = image.getWidth() / 2;
        int margin = half / 8;
        for (int y = qy * half + margin; y < (qy + 1) * half - margin; y += 3) {
            for (int x = qx * half + margin; x < (qx + 1) * half - margin; x += 3) {
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

    private static final Pattern STREAM =
            Pattern.compile("stream\r?\n(.*?)\r?\nendstream", Pattern.DOTALL);

    /** The data of the input's /Im0 stream: the fixture's single image stream. */
    private static byte[] imageStream(byte[] pdf) {
        Matcher m = STREAM.matcher(new String(pdf, StandardCharsets.ISO_8859_1));
        m.find();  // the content stream
        if (!m.find()) fail("fixture has no image stream");
        return latin1(m.group(1));
    }

    /** True when |needle| is in the file, or in any of its streams once inflated. */
    private static boolean containsStream(byte[] pdf, byte[] needle) {
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        String target = new String(needle, StandardCharsets.ISO_8859_1);
        if (text.contains(target)) return true;
        Matcher m = STREAM.matcher(text);
        while (m.find()) {
            byte[] inflated = inflate(latin1(m.group(1)));
            if (inflated != null && new String(inflated, StandardCharsets.ISO_8859_1).contains(target)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /** Every image the page's content draws, following forms and inline images. */
    private static List<Redacted> drawnImages(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            DrawnImages drawn = new DrawnImages();
            drawn.processPage(doc.getPage(0));
            // Decode while the document is open.
            List<Redacted> decoded = new ArrayList<>();
            for (PDImage image : drawn.images) decoded.add(Redacted.of(image));
            return decoded;
        }
    }

    private static final class DrawnImages extends PDFStreamEngine {
        final List<PDImage> images = new ArrayList<>();

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("BI".equals(operator.getName())) {
                images.add(new PDInlineImage(operator.getImageParameters(), operator.getImageData(),
                        getResources()));
            } else if ("Do".equals(operator.getName()) && !operands.isEmpty()
                    && operands.get(0) instanceof COSName name) {
                var xObject = getResources().getXObject(name);
                if (xObject instanceof PDImageXObject image) {
                    images.add(image);
                } else if (xObject instanceof PDFormXObject form) {
                    showForm(form);
                }
            }
        }
    }
}
