package stirling.software.jpdfium.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import stirling.software.jpdfium.model.Rect;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class TaggedRegionRedactTest {

    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final float SIZE = 12f;
    private static final float LEFT = 72f;
    private static final String CUT_LINE = "Please redact SECRET now";
    private static final String DESCRIBED_LINE = "Call SECRET today";

    @Test
    void textLeftOnEitherSideOfARegionKeepsItsTag() throws Exception {
        byte[] out = redactRegion(wordRect("Please redact ", "SECRET", 700f));
        try (PDDocument doc = Loader.loadPDF(out)) {
            List<Integer> textMcids = textShowMcids(doc.getPage(0));
            assertEquals(List.of(0, 0, 1, 2), textMcids);
        }
    }

    @Test
    void removedTextTakesItsReplacementTextWithIt() throws Exception {
        byte[] out = redactRegion(Rect.of(60f, 662f, 300f, 24f));
        try (PDDocument doc = Loader.loadPDF(out)) {
            PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
            List<PDStructureElement> elements = new ArrayList<>();
            collect(root, elements);
            assertEquals(4, elements.size(), "structure elements are kept");
            for (PDStructureElement e : elements) {
                assertNull(e.getActualText(), e.getStructureType() + " still has ActualText");
            }
            PDResources res = doc.getPage(0).getResources();
            for (COSName name : res.getPropertiesNames()) {
                PDPropertyList props = res.getProperties(name);
                assertFalse(props.getCOSObject().containsKey(COSName.ACTUAL_TEXT),
                        "marked-content properties still have ActualText");
            }
            assertEquals(List.of(0, 2), textShowMcids(doc.getPage(0)));
        }
    }

    @Test
    void untouchedReplacementTextSurvives() throws Exception {
        byte[] out = redactRegion(wordRect("Please redact ", "SECRET", 700f));
        try (PDDocument doc = Loader.loadPDF(out)) {
            List<PDStructureElement> elements = new ArrayList<>();
            collect(doc.getDocumentCatalog().getStructureTreeRoot(), elements);
            assertTrue(elements.stream().anyMatch(e -> DESCRIBED_LINE.equals(e.getActualText())));
        }
    }

    private static byte[] redactRegion(Rect rect) throws IOException {
        try (RedactionSession session = RedactionSession.open(taggedPdf())) {
            session.markRegion(0, rect, 0xFF000000);
            session.commitAll();
            return session.saveBytes();
        }
    }

    private static Rect wordRect(String before, String word, float baseline)
            throws IOException {
        float x = LEFT + width(before);
        return Rect.of(x - 0.3f, baseline - 5f, width(word) + 0.6f, 18f);
    }

    private static float width(String s) throws IOException {
        return FONT.getStringWidth(s) / 1000f * SIZE;
    }

    private static List<Integer> textShowMcids(PDPage page) throws IOException {
        List<Object> tokens = new PDFStreamParser(page).parse();
        Deque<Integer> open = new ArrayDeque<>();
        List<COSBase> operands = new ArrayList<>();
        List<Integer> mcids = new ArrayList<>();
        PDResources res = page.getResources();
        for (Object token : tokens) {
            if (!(token instanceof Operator operator)) {
                operands.add((COSBase) token);
                continue;
            }
            switch (operator.getName()) {
                case "BMC" -> open.push(-1);
                case "BDC" -> open.push(mcid(operands.get(1), res));
                case "EMC" -> open.pop();
                case "Tj", "TJ", "'", "\"" -> {
                    assertFalse(open.isEmpty(), "text shown outside marked content");
                    mcids.add(open.peek());
                }
                default -> {}
            }
            operands.clear();
        }
        assertTrue(open.isEmpty(), "unbalanced marked content");
        return mcids;
    }

    private static int mcid(COSBase props, PDResources res) throws IOException {
        COSDictionary dict = props instanceof COSName name
                ? res.getProperties(name).getCOSObject()
                : (COSDictionary) props;
        COSBase id = dict.getDictionaryObject(COSName.MCID);
        return id instanceof COSNumber n ? n.intValue() : -1;
    }

    private static void collect(PDStructureNode node, List<PDStructureElement> out) {
        for (Object kid : node.getKids()) {
            if (kid instanceof PDStructureElement e) {
                out.add(e);
                collect(e, out);
            }
        }
    }

    private static byte[] taggedPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement document = new PDStructureElement("Document", root);
            root.appendKid(document);
            COSArray parents = new COSArray();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                tagged(cs, document, page, parents, 0, null, CUT_LINE, 700f);
                tagged(cs, document, page, parents, 1, DESCRIBED_LINE, DESCRIBED_LINE, 670f);
                tagged(cs, document, page, parents, 2, null, "Keep this line", 640f);
            }
            page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
            PDNumberTreeNode parentTree = new PDNumberTreeNode(PDStructureElement.class);
            COSArray nums = new COSArray();
            nums.add(COSInteger.ZERO);
            nums.add(parents);
            parentTree.getCOSObject().setItem(COSName.NUMS, nums);
            root.setParentTree(parentTree);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void tagged(PDPageContentStream cs, PDStructureElement parent, PDPage page,
            COSArray parents, int mcid, String actualText, String text, float baseline)
            throws IOException {
        PDStructureElement p = new PDStructureElement("P", parent);
        p.setPage(page);
        p.getCOSObject().setItem(COSName.K, COSInteger.get(mcid));
        parent.appendKid(p);
        parents.add(p);
        COSDictionary props = new COSDictionary();
        props.setInt(COSName.MCID, mcid);
        if (actualText != null) {
            p.setActualText(actualText);
            props.setString(COSName.ACTUAL_TEXT, actualText);
        }
        cs.beginMarkedContent(COSName.P, PDPropertyList.create(props));
        cs.beginText();
        cs.setFont(FONT, SIZE);
        cs.newLineAtOffset(LEFT, baseline);
        cs.showText(text);
        cs.endText();
        cs.endMarkedContent();
    }
}
