package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfFontSubsetterTest {

    @Test
    void subsetsSuppliedFontProgramsAndPreservesText() throws Exception {
        assertTrue(PdfFontEmbedder.isAvailable(), "native qpdf font replacement unavailable");
        URL url = PdfFontSubsetterTest.class.getResource("/pdfs/general/basic-text.pdf");
        assertTrue(url != null, "basic-text.pdf fixture is missing");
        Path source = Path.of(url.toURI());
        byte[] input = Files.readAllBytes(source);

        String text;
        Map<String, PdfFontSubsetter.Request> requests = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(input)) {
            text = new PDFTextStripper().getText(doc);
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
                }
            }
        }
        assertTrue(!requests.isEmpty(), "fixture must contain an embedded TrueType font");

        byte[] output = PdfFontSubsetter.subset(input, requests);
        assertTrue(output.length <= input.length, "subsetting must not grow the document");
        try (PDDocument doc = Loader.loadPDF(output)) {
            assertEquals(text, new PDFTextStripper().getText(doc),
                    "subset fonts must preserve text extraction");
        }
    }
}
