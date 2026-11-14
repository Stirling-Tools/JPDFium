package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfFontSubsetterTest {

    @Test
    void standaloneCffProgramIsLeftUntouched() throws Exception {
        assertTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        URL url = PdfFontSubsetterTest.class.getResource(
                "/pdfs/general/pdfjs_annotation-choice-widget.pdf");
        assertTrue(url != null, "CFF fixture is missing");
        byte[] input = Files.readAllBytes(Path.of(url.toURI()));

        String baseName = null;
        byte[] program = null;
        int[] codepoints = {0x41, 0x42};
        try (PDDocument doc = Loader.loadPDF(input)) {
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    if (font.getFontDescriptor() == null
                            || font.getFontDescriptor().getFontFile3() == null) {
                        continue;
                    }
                    var file = font.getFontDescriptor().getFontFile3();
                    String subtype = file.getCOSObject().getNameAsString(COSName.SUBTYPE);
                    if (!"Type1C".equals(subtype) && !"CIDFontType0C".equals(subtype)) {
                        continue;
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (var in = file.createInputStream()) {
                        in.transferTo(bytes);
                    }
                    baseName = font.getName();
                    program = bytes.toByteArray();
                    break;
                }
                if (program != null) break;
            }
        }
        assertTrue(program != null, "fixture must contain a standalone CFF program");

        byte[] output = PdfFontSubsetter.subset(input, Map.of(
                baseName, new PdfFontSubsetter.Request(program, codepoints)));
        assertArrayEquals(input, output,
                "bare CFF programs must be left untouched instead of throwing or corrupting the PDF");
    }

    @Test
    void subsetsSuppliedFontProgramsAndPreservesText() throws Exception {
        assertTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        URL url = PdfFontSubsetterTest.class.getResource("/pdfs/general/basic-text.pdf");
        assertTrue(url != null, "basic-text.pdf fixture is missing");
        Path source = Path.of(url.toURI());
        byte[] input = Files.readAllBytes(source);

        String text;
        Map<String, PdfFontSubsetter.Request> requests = new LinkedHashMap<>();
        Map<String, Integer> originalSizes = new LinkedHashMap<>();
        BufferedImage beforeImage;
        try (PDDocument doc = Loader.loadPDF(input)) {
            text = new PDFTextStripper().getText(doc);
            beforeImage = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            Set<Integer> codepoints = text.codePoints().boxed().collect(Collectors.toCollection(TreeSet::new));
            int[] used = codepoints.stream().mapToInt(Integer::intValue).toArray();
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    if (font.getFontDescriptor() == null
                            || font.getFontDescriptor().getFontFile2() == null) {
                        continue;
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (var in = font.getFontDescriptor().getFontFile2().createInputStream()) {
                        in.transferTo(bytes);
                    }
                    requests.put(font.getName(),
                            new PdfFontSubsetter.Request(bytes.toByteArray(), used));
                    originalSizes.put(font.getName(), bytes.size());
                }
            }
        }
        assertTrue(!requests.isEmpty(), "fixture must contain an embedded TrueType font");

        byte[] output = PdfFontSubsetter.subset(input, requests);
        assertTrue(output.length <= input.length, "subsetting must not grow the document");
        try (PDDocument doc = Loader.loadPDF(output)) {
            assertEquals(text, new PDFTextStripper().getText(doc),
                    "subset fonts must preserve text extraction");

            boolean replaced = false;
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    Integer original = originalSizes.get(font.getName());
                    if (original == null || font.getFontDescriptor() == null
                            || font.getFontDescriptor().getFontFile2() == null) {
                        continue;
                    }
                    long installed;
                    try (var in = font.getFontDescriptor().getFontFile2().createInputStream()) {
                        installed = in.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    assertTrue(installed < original,
                            "font stream must shrink after subsetting " + font.getName());
                    replaced = true;
                }
            }
            assertTrue(replaced, "at least one supplied font stream must be replaced");

            BufferedImage afterImage = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            assertEquals(beforeImage.getWidth(), afterImage.getWidth());
            assertEquals(beforeImage.getHeight(), afterImage.getHeight());
            double delta = 0;
            for (int y = 0; y < beforeImage.getHeight(); y++) {
                for (int x = 0; x < beforeImage.getWidth(); x++) {
                    int a = beforeImage.getRGB(x, y);
                    int b = afterImage.getRGB(x, y);
                    delta += (Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                                    + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                                    + Math.abs((a & 0xFF) - (b & 0xFF)))
                            / 3.0;
                }
            }
            double mean = delta / (beforeImage.getWidth() * beforeImage.getHeight());
            assertTrue(mean < 1.0, "subset rendering changed glyphs: mean delta " + mean);
        }
    }
}
