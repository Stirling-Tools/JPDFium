package stirling.software.jpdfium.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType0;
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

    @Test
    void convertsSampledAxialShadingToCmyk() throws Exception {
        assertTrue(PdfShadingConverter.isAvailable(), "native shading rewrite unavailable");

        byte[] input = sampledGradient();
        byte[] output = PdfShadingConverter.toCmyk(input);
        assertNotNull(output, "sampled shading must be rewritten");

        try (PDDocument doc = Loader.loadPDF(output)) {
            PDResources resources = doc.getPage(0).getResources();
            Iterator<COSName> names = resources.getShadingNames().iterator();
            assertTrue(names.hasNext(), "output has no shading resource");
            PDShadingType2 shading = (PDShadingType2) resources.getShading(names.next());
            assertInstanceOf(PDDeviceCMYK.class, shading.getColorSpace());
            assertInstanceOf(PDFunctionType0.class, shading.getFunction());
            assertEquals(
                    8,
                    shading.getFunction()
                            .getCOSObject()
                            .getCOSArray(COSName.getPDFName("Range"))
                            .size());
        }
    }

    @Test
    void pdfx1aOutputConvertsSimpleShading() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_1A)
                .useCompactCmyk()
                .title("Shading")
                .build();
        byte[] output = PdfXConverter.applyMetadata(axialGradient(), options);
        try (PDDocument doc = Loader.loadPDF(output)) {
            PDResources resources = doc.getPage(0).getResources();
            Iterator<COSName> names = resources.getShadingNames().iterator();
            assertTrue(names.hasNext(), "PDF/X output has no shading resource");
            PDShadingType2 shading = (PDShadingType2) resources.getShading(names.next());
            assertInstanceOf(PDDeviceCMYK.class, shading.getColorSpace());
        }
    }

    private static byte[] sampledGradient() throws IOException {
        try (PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                COSStream functionStream = new COSStream()) {
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

            functionStream.setItem(COSName.FUNCTION_TYPE, COSInteger.get(0));
            functionStream.setItem(COSName.DOMAIN, unitRange(1));
            functionStream.setItem(COSName.RANGE, unitRange(3));
            COSArray size = new COSArray();
            size.add(COSInteger.get(2));
            functionStream.setItem(COSName.SIZE, size);
            functionStream.setItem(COSName.BITS_PER_SAMPLE, COSInteger.get(8));
            functionStream.setItem(COSName.ORDER, COSInteger.get(1));
            functionStream.setItem(COSName.DECODE, unitRange(3));
            try (OutputStream stream = functionStream.createOutputStream()) {
                stream.write(new byte[] {(byte) 255, 0, 0, 0, 0, (byte) 255});
            }
            shading.setFunction(new PDFunctionType0(functionStream));

            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.shadingFill(shading);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static COSArray unitRange(int components) {
        COSArray range = new COSArray();
        for (int i = 0; i < components; i++) {
            range.add(COSInteger.ZERO);
            range.add(COSInteger.ONE);
        }
        return range;
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
