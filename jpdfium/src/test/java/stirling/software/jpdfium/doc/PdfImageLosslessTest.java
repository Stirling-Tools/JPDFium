package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.awt.image.WritableRaster;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.imageio.ImageIO;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.ColorBindings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Lossless image sources must not become JPEGs unless explicitly requested. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfImageLosslessTest {

    @Test
    void flateLineArtIsLeftAloneByDefaultAndConvertedOnlyOnRequest() throws Exception {
        assumeTrue(ColorBindings.imageConversionAvailable(),
                "CMYK image conversion unavailable in this native build");

        byte[] input = flateLineArtPdf();
        try (PdfDocument document = PdfDocument.open(input)) {
            assertEquals(0, PdfColorConverter.convertImagesToCmyk(document, 90),
                    "a Flate-only image must not be converted by default");
            assertTrue(filters(document.saveBytes()).contains(COSName.FLATE_DECODE),
                    "Flate image was re-encoded by default");
        }

        try (PdfDocument document = PdfDocument.open(input)) {
            assertTrue(PdfColorConverter.convertImagesToCmyk(document, 90, true) > 0,
                    "explicit lossless opt-in must convert the image");
            assertTrue(filters(document.saveBytes()).contains(COSName.DCT_DECODE),
                    "explicit lossless opt-in did not produce a JPEG");
        }
    }

    @Test
    void strictConversionRejectsSkippedLosslessImages() throws Exception {
        assumeTrue(ColorBindings.imageConversionAvailable(),
                "CMYK image conversion unavailable in this native build");
        try (PdfDocument document = PdfDocument.open(flateLineArtPdf())) {
            assertEquals(-1,
                    ColorBindings.pageImagesToCmyk(document.nativeHandle(), 0, 90,
                            ColorBindings.IMAGE_STRICT),
                    "strict conversion must fail when an RGB image is skipped");
        }
    }

    @Test
    void imagePixelsUseTheSameLutAsTheVectorPath() throws Exception {
        assumeTrue(ColorBindings.imageConversionAvailable(),
                "CMYK image conversion unavailable in this native build");

        int red = 10;
        int green = 120;
        int blue = 240;
        IccCmykTransform transform = new IccCmykTransform(PdfIccProfiles.compactCmyk());
        int[] expected = new int[4];
        transform.toCmyk(red, green, blue, expected);

        byte[] input = solidJpegPdf(red, green, blue);
        try (PdfDocument document = PdfDocument.open(input)) {
            int converted = ColorBindings.pageImagesToCmyk(document.nativeHandle(), 0, 95, 0,
                    transform.lutBytes(), IccCmykTransform.lutGrid());
            assertEquals(1, converted, "solid JPEG should be converted");
            byte[] output = document.saveBytes();

            try (PDDocument pdf = Loader.loadPDF(output)) {
                PDImageXObject image = firstImage(pdf);
                assertNotNull(image, "converted image missing");
                WritableRaster raster = image.getRawRaster();
                assertEquals(4, raster.getNumBands(), "image did not become DeviceCMYK");
                int[] actual = raster.getPixel(raster.getWidth() / 2, raster.getHeight() / 2,
                        (int[]) null);
                for (int channel = 0; channel < 4; channel++) {
                    assertTrue(Math.abs(actual[channel] - expected[channel]) <= 12,
                            "image and vector LUT diverged in channel " + channel
                                    + ": image=" + actual[channel]
                                    + " vector=" + expected[channel]);
                }
            }
        }
    }

    @Test
    void sharedImageIsConvertedOnEveryPage() throws Exception {
        assumeTrue(ColorBindings.imageConversionAvailable(),
                "CMYK image conversion unavailable in this native build");

        byte[] input = sharedJpegPdf();
        byte[] output;
        try (PdfDocument document = PdfDocument.open(input)) {
            assertTrue(PdfColorConverter.convertImagesToCmyk(document, 90) > 0,
                    "shared JPEG must convert");
            output = document.saveBytes();
        }

        try (PDDocument document = Loader.loadPDF(output)) {
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                PDImageXObject image = firstImage(document, page);
                assertNotNull(image, "page " + page + " lost its image");
                assertEquals("DeviceCMYK", image.getColorSpace().getName(),
                        "page " + page + " still references the RGB image");
            }
        }
    }

    private static PDImageXObject firstImage(PDDocument document) throws Exception {
        return firstImage(document, 0);
    }

    private static PDImageXObject firstImage(PDDocument document, int pageIndex) throws Exception {
        for (COSName name : document.getPage(pageIndex).getResources().getXObjectNames()) {
            if (document.getPage(pageIndex).getResources().getXObject(name)
                    instanceof PDImageXObject image) {
                return image;
            }
        }
        return null;
    }

    private static byte[] solidJpegPdf(int red, int green, int blue) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        int rgb = (red << 16) | (green << 8) | blue;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpeg", jpeg), "JPEG writer missing");

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDImageXObject xobject = PDImageXObject.createFromByteArray(
                    document, jpeg.toByteArray(), "solid");
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(xobject, 20, 20, 200, 200);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] sharedJpegPdf() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                image.setRGB(x, y, 0x3366CC);
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpeg", jpeg), "JPEG writer missing");
        try (PDDocument document = new PDDocument()) {
            PDImageXObject xobject = PDImageXObject.createFromByteArray(
                    document, jpeg.toByteArray(), "shared");
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(xobject, 20, 20, 200, 200);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static Set<COSName> filters(byte[] pdf) throws Exception {
        Set<COSName> filters = new LinkedHashSet<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDPage page = document.getPage(0);
            for (COSName name : page.getResources().getXObjectNames()) {
                COSBase object = page.getResources().getXObject(name).getCOSObject();
                if (object instanceof COSDictionary dictionary) {
                    COSBase filter = dictionary.getDictionaryObject(COSName.FILTER);
                    if (filter instanceof COSName filterName) {
                        filters.add(filterName);
                    } else if (filter instanceof COSArray array) {
                        for (COSBase item : array) {
                            if (item instanceof COSName filterName) filters.add(filterName);
                        }
                    }
                }
            }
        }
        return filters;
    }

    private static byte[] flateLineArtPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    image.setRGB(x, y, 0xFFFFFF);
                }
            }
            for (int x = 0; x < 32; x++) {
                image.setRGB(x, 16, 0x000000);
            }
            PDImageXObject xobject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(xobject, 20, 20, 200, 200);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
