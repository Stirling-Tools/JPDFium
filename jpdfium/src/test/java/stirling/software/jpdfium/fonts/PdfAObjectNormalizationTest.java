package stirling.software.jpdfium.fonts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.doc.PdfAConverter;
import stirling.software.jpdfium.panama.NativeRuntime;

class PdfAObjectNormalizationTest {

    private static byte[] fixture(String resource) throws Exception {
        try (InputStream in = PdfAObjectNormalizationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " fixture required");
            return in.readAllBytes();
        }
    }

    private static void assumeNative() {
        assumeTrue(NativeRuntime.isFull() && PdfFontRewriter.isAvailable());
    }

    @Test
    void flattensOrphanWidgetAppearanceForPdfA1b() throws Exception {
        assumeNative();
        byte[] input = fixture("/pdfs/general/checkbox-zadb-appearance.pdf");

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawWidget = false;
            for (var page : doc.getPages()) {
                for (var annotation : page.getAnnotations()) {
                    COSDictionary annot = annotation.getCOSObject();
                    if (!COSName.WIDGET.equals(annot.getCOSName(COSName.SUBTYPE))) continue;
                    sawWidget = true;
                    COSDictionary ap = annot.getCOSDictionary(COSName.AP);
                    assertNotNull(ap, "widget must keep an appearance dictionary");
                    COSBase normal = ap.getDictionaryObject(COSName.N);
                    assertTrue(
                            normal instanceof COSStream,
                            "orphan widget /AP /N must be flattened to a stream");
                }
            }
            assertTrue(sawWidget, "fixture must contain a widget annotation");
        }
    }

    @Test
    void replacesV4IccProfileWithV2ForPdfA1b() throws Exception {
        assumeNative();
        byte[] input =
                fixture(
                        "/pdfs/redact/23_Narrative of the Life of Frederick Douglass, an Am.pdf");

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            boolean sawIcc = false;
            for (var page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) continue;
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(name);
                    if (!(xObject instanceof PDImageXObject image)) continue;
                    COSBase colorSpace =
                            image.getCOSObject().getDictionaryObject(COSName.COLORSPACE);
                    if (!(colorSpace instanceof COSArray array) || array.size() < 2) continue;
                    COSBase profileObject = array.getObject(1);
                    if (!(profileObject instanceof COSStream profile)) continue;
                    byte[] bytes;
                    try (InputStream in = profile.createInputStream()) {
                        bytes = in.readAllBytes();
                    }
                    if (bytes.length < 40 || bytes[36] != 'a' || bytes[37] != 'c'
                            || bytes[38] != 's' || bytes[39] != 'p') {
                        continue;
                    }
                    sawIcc = true;
                    assertTrue(
                            bytes[8] == 2,
                            "PDF/A-1 must not embed the ICC v4 profile from the source image");
                }
            }
            assertTrue(sawIcc, "fixture must contain an ICCBased image profile");
        }
    }

    @Test
    void stripsUndefinedContentOperatorsForPdfA1b() throws Exception {
        assumeNative();
        byte[] input = fixture("/pdfs/repair/pdfjs-issue2391-1.pdf");

        FontRewriteResult result = PdfAConverter.toPdfA1b(input);
        assertTrue(result.modified(), "expected PDF/A conversion: " + result.reportJson());

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            for (var page : doc.getPages()) {
                COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
                List<COSStream> streams = new ArrayList<>();
                if (contents instanceof COSStream stream) {
                    streams.add(stream);
                } else if (contents instanceof COSArray array) {
                    for (int i = 0; i < array.size(); i++) {
                        if (array.getObject(i) instanceof COSStream stream) streams.add(stream);
                    }
                }
                for (COSStream stream : streams) {
                    byte[] bytes;
                    try (InputStream in = stream.createInputStream()) {
                        bytes = in.readAllBytes();
                    }
                    assertFalse(
                            new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                                    .contains("undefined"),
                            "page content must not contain the undefined operator");
                }
            }
        }
    }
}
