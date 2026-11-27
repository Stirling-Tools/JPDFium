package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.model.Rect;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class RedactionCoverArtifactTest {

    private static final int OPAQUE_BLACK = 0xFF000000;

    @Test
    void wordCoversAreArtifacts() throws Exception {
        assertCoversAreArtifacts(redactWords());
    }

    @Test
    void regionCoversAreArtifacts() throws Exception {
        try (RedactionSession session = RedactionSession.open(textPdf())) {
            session.markRegion(0, Rect.of(60f, 690f, 200f, 30f), OPAQUE_BLACK);
            session.commitAll();
            assertCoversAreArtifacts(session.saveBytes());
        }
    }

    private static byte[] redactWords() throws IOException {
        RedactOptions options =
                RedactOptions.builder()
                        .addWord("SECRET")
                        .boxColor(OPAQUE_BLACK)
                        .removeContent(true)
                        .build();
        try (PdfDocument doc = PdfDocument.open(textPdf())) {
            RedactResult result = PdfRedactor.redact(doc, options);
            assertEquals(1, result.totalMatches());
            return result.document().saveBytes();
        }
    }

    private static byte[] textPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f);
                cs.newLineAtOffset(72f, 700f);
                cs.showText("Please redact SECRET now");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void assertCoversAreArtifacts(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<Object> tokens = new PDFStreamParser(doc.getPage(0)).parse();
            Deque<Boolean> open = new ArrayDeque<>();
            List<COSBase> operands = new ArrayList<>();
            int fills = 0;
            for (Object token : tokens) {
                if (!(token instanceof Operator operator)) {
                    operands.add((COSBase) token);
                    continue;
                }
                switch (operator.getName()) {
                    case "BDC", "BMC" ->
                            open.push(!operands.isEmpty() && COSName.ARTIFACT.equals(operands.get(0)));
                    case "EMC" -> open.pop();
                    case "f", "F", "f*" -> {
                        assertTrue(open.contains(true), "cover fill drawn outside an Artifact");
                        fills++;
                    }
                    default -> {}
                }
                operands.clear();
            }
            assertTrue(fills > 0, "expected a cover fill");
            assertTrue(open.isEmpty(), "unbalanced marked content");
        }
    }
}
