package stirling.software.jpdfium.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfShadingConverterTest {

    @Test
    void convertsAxialRgbShadingToCmyk() throws Exception {
        assertTrue(PdfShadingConverter.isAvailable(), "native shading rewrite unavailable");

        byte[] input = axialGradient();
        byte[] output = PdfShadingConverter.toCmyk(input);
        assertNotNull(output, "simple axial shading must be rewritten");

        try (PDDocument doc = Loader.loadPDF(output)) {
            PDResources resources = doc.getPage(0).getResources();
            Iterator<COSName> names = resources.getShadingNames().iterator();
            assertTrue(names.hasNext(), "output has no shading resource");
            PDShadingType2 shading = (PDShadingType2) resources.getShading(names.next());
            assertNotNull(shading);
            assertInstanceOf(PDDeviceCMYK.class, shading.getColorSpace());
            PDFunction function = shading.getFunction();
            assertInstanceOf(PDFunctionType2.class, function);
            COSArray c0 = function.getCOSObject().getCOSArray(COSName.getPDFName("C0"));
            COSArray c1 = function.getCOSObject().getCOSArray(COSName.getPDFName("C1"));
            assertEquals(4, c0.size());
            assertEquals(4, c1.size());
        }
    }

    private static byte[] axialGradient() throws IOException {
        try (PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(200, 200));
            doc.addPage(page);

            PDShadingType2 shading = new PDShadingType2(new COSDictionary());
            shading.setShadingType(2);
            shading.setColorSpace(PDDeviceRGB.INSTANCE);
            COSArray coords = new COSArray();
            coords.add(COSFloat.get("20"));
            coords.add(COSFloat.get("0"));
            coords.add(COSFloat.get("180"));
            coords.add(COSFloat.get("0"));
            shading.setCoords(coords);
            COSDictionary function = new COSDictionary();
            function.setInt(COSName.getPDFName("FunctionType"), 2);
            function.setInt(COSName.getPDFName("N"), 1);
            COSArray c0 = new COSArray();
            c0.add(COSFloat.get("1"));
            c0.add(COSFloat.get("0"));
            c0.add(COSFloat.get("0"));
            COSArray c1 = new COSArray();
            c1.add(COSFloat.get("0"));
            c1.add(COSFloat.get("0"));
            c1.add(COSFloat.get("1"));
            function.setItem(COSName.getPDFName("C0"), c0);
            function.setItem(COSName.getPDFName("C1"), c1);
            shading.setFunction(new PDFunctionType2(function));

            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.shadingFill(shading);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
