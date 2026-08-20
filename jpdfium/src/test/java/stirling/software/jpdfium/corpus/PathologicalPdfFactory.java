package stirling.software.jpdfium.corpus;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates pathological, malformed, and edge-case PDF specimens for fuzzing and robustness testing.
 */
public final class PathologicalPdfFactory {

    private PathologicalPdfFactory() {}

    public record Specimen(String name, String category, byte[] bytes, boolean expectedOpenable) {}

    public static Map<String, Specimen> generateAll() {
        Map<String, Specimen> map = new LinkedHashMap<>();
        byte[] valid = createValidBasePdf();

        // Structural & XRef
        add(map, "xref-truncated", "structural", corruptXrefTruncate(valid), false);
        add(map, "xref-random-ascii", "structural", corruptXrefRandom(valid), false);
        add(map, "startxref-overflow", "structural", mutateStartxref(valid, "9999999999"), false);
        add(map, "startxref-negative", "structural", mutateStartxref(valid, "-100"), false);
        add(map, "trailer-stripped", "structural", stripTrailer(valid), false);
        add(map, "deep-indirect-chain", "structural", createDeepIndirectChain(50), true);
        add(map, "self-referencing-dict", "structural", createSelfReferencingDictionary(), true);
        add(map, "cyclic-indirect-refs", "structural", createCyclicIndirectReferences(), true);
        add(map, "duplicate-object-ids", "structural", injectDuplicateObjectIds(valid), true);

        // Page trees
        add(map, "deep-page-tree-50-levels", "pagetree", createDeepPageTree(50), true);
        add(map, "cyclic-page-tree", "pagetree", createCyclicPageTree(), false);
        add(map, "empty-page-tree", "pagetree", createEmptyPageTree(), false);
        add(map, "duplicate-kids-pages", "pagetree", createDuplicateKidsPageTree(), true);
        add(map, "missing-mediabox-inherited", "pagetree", createMissingMediaBoxTree(), true);

        // Geometry & coordinates
        add(map, "zero-dimension-mediabox", "geometry", createCustomBox("[0 0 0 0]"), true);
        add(map, "inverted-mediabox", "geometry", createCustomBox("[612 792 0 0]"), true);
        add(map, "negative-dimension-box", "geometry", createCustomBox("[-100 -200 -10 -20]"), true);
        add(map, "astronomical-mediabox", "geometry", createCustomBox("[-1000000 -1000000 1000000 1000000]"), true);
        add(map, "non-integer-rotation", "geometry", createPageWithProperty("/Rotate 45"), true);
        add(map, "string-rotation-value", "geometry", createPageWithProperty("/Rotate (90)"), true);

        // Streams & filters
        add(map, "stream-length-excessive", "stream", mutateStreamLength(valid, "999999"), true);
        add(map, "stream-length-negative", "stream", mutateStreamLength(valid, "-50"), true);
        add(map, "stream-length-zero", "stream", mutateStreamLength(valid, "0"), true);
        add(map, "stream-truncated-flate", "stream", createTruncatedFlateStream(), true);
        add(map, "stream-missing-endstream", "stream", stripEndstream(valid), true);
        add(map, "stream-corrupt-adler32", "stream", createCorruptAdler32Flate(), true);

        // Content stream operators
        add(map, "ops-unbalanced-q-stack-deep", "operators", createContentStreamWithOps("q\n".repeat(60) + "BT /F1 12 Tf (Deep q) Tj ET\n"), true);
        add(map, "ops-excessive-q-pops", "operators", createContentStreamWithOps("Q\n".repeat(30) + "BT /F1 12 Tf (Underflow Q) Tj ET\n"), true);
        add(map, "ops-unclosed-bt-blocks", "operators", createContentStreamWithOps("BT BT /F1 12 Tf (Nested BT) Tj ET\n"), true);
        add(map, "ops-orphan-et", "operators", createContentStreamWithOps("ET /F1 12 Tf (Orphan ET) Tj\n"), true);
        add(map, "ops-unterminated-string", "operators", createContentStreamWithOps("BT /F1 12 Tf (Unterminated literal string without close paren\nET\n"), true);
        add(map, "ops-malformed-hex-string", "operators", createContentStreamWithOps("BT /F1 12 Tf <48656C6C6F> Tj ET\n"), true);
        add(map, "ops-extreme-cm-matrix", "operators", createContentStreamWithOps("1e20 0 0 1e20 0 0 cm BT /F1 12 Tf (Extreme CM) Tj ET\n"), true);

        // Fonts & text
        add(map, "font-missing-widths", "fonts", createFontMissingWidths(), true);
        add(map, "font-zero-matrix", "fonts", createFontZeroMatrix(), true);
        add(map, "font-broken-tounicode-cmap", "fonts", createBrokenToUnicodeCMap(), true);
        add(map, "text-unpaired-utf16-surrogates", "fonts", createUnpairedSurrogatesPdf(), true);

        // Annotations, outlines & metadata
        add(map, "outlines-cyclic-loop", "metadata", createCyclicBookmarks(), true);
        add(map, "outlines-out-of-bounds-page", "metadata", createOutOfBoundsBookmark(), true);
        add(map, "annot-broken-ap-reference", "metadata", createBrokenAppearanceAnnotation(), true);
        add(map, "metadata-malformed-xmp", "metadata", createMalformedXmpPdf(), true);

        return map;
    }

    private static void add(Map<String, Specimen> map, String name, String category, byte[] bytes, boolean expectedOpenable) {
        map.put(name, new Specimen(name, category, bytes, expectedOpenable));
    }

    // Generator helpers

    public static byte[] createValidBasePdf() {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= 2; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Pathological Base Page " + i);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] mutateStartxref(byte[] pdf, String newOffset) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int pos = s.lastIndexOf("startxref");
        if (pos < 0) return pdf;
        return (s.substring(0, pos) + "startxref\n" + newOffset + "\n%%EOF").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] corruptXrefTruncate(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int pos = s.lastIndexOf("xref");
        if (pos < 0) return pdf;
        int half = pos + (s.length() - pos) / 3;
        return Arrays.copyOf(pdf, half);
    }

    private static byte[] corruptXrefRandom(byte[] pdf) {
        byte[] copy = pdf.clone();
        String s = new String(copy, StandardCharsets.ISO_8859_1);
        int xrefPos = s.lastIndexOf("xref");
        if (xrefPos < 0) return copy;
        int trailerPos = s.indexOf("trailer", xrefPos);
        if (trailerPos < 0) trailerPos = copy.length;
        for (int i = xrefPos + 5; i < trailerPos && i < copy.length; i++) {
            copy[i] = (byte) ('0' + (i % 10));
        }
        return copy;
    }

    private static byte[] stripTrailer(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int t = s.lastIndexOf("trailer");
        int sx = s.lastIndexOf("startxref");
        if (t < 0 || sx < 0) return pdf;
        return (s.substring(0, t) + s.substring(sx)).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] mutateStreamLength(byte[] pdf, String newLen) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int pos = s.indexOf("/Length ");
        if (pos < 0) return pdf;
        int start = pos + 8;
        int end = start;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        return (s.substring(0, start) + newLen + s.substring(end)).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] stripEndstream(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int pos = s.indexOf("endstream");
        if (pos < 0) return pdf;
        return (s.substring(0, pos) + s.substring(pos + 9)).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] injectDuplicateObjectIds(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int pos = s.indexOf("2 0 obj");
        if (pos < 0) return pdf;
        String dup = "2 0 obj\n<< /Type /Catalog /Pages 3 0 R >>\nendobj\n";
        return (s.substring(0, pos) + dup + s.substring(pos)).getBytes(StandardCharsets.ISO_8859_1);
    }

    // Procedural raw PDF constructors

    private static byte[] createDeepIndirectChain(int depth) {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /DeepChain ").append(depth + 10).append(" 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n");
        sb.append("4 0 obj\n<< /Length 30 >>\nstream\nBT /F1 12 Tf (Deep) Tj ET\nendstream\nendobj\n");

        for (int i = 10; i < depth + 10; i++) {
            sb.append(i).append(" 0 obj\n<< /Next ").append(i + 1).append(" 0 R /Value ").append(i).append(" >>\nendobj\n");
        }
        sb.append(depth + 10).append(" 0 obj\n(Terminal Node)\nendobj\n");

        appendTrailer(sb, depth + 11, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createSelfReferencingDictionary() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Self 1 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /Loop 2 0 R >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /SelfPage 3 0 R >>\nendobj\n");
        appendTrailer(sb, 4, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createCyclicIndirectReferences() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /RefA 4 0 R >>\nendobj\n");
        sb.append("4 0 obj\n<< /RefB 5 0 R >>\nendobj\n");
        sb.append("5 0 obj\n<< /RefA 4 0 R >>\nendobj\n");
        appendTrailer(sb, 6, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createDeepPageTree(int depth) {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        for (int i = 2; i < depth + 2; i++) {
            sb.append(i).append(" 0 obj\n<< /Type /Pages /Kids [").append(i + 1).append(" 0 R] /Count 1 >>\nendobj\n");
        }
        int pageId = depth + 2;
        sb.append(pageId).append(" 0 obj\n<< /Type /Page /Parent ").append(pageId - 1).append(" 0 R /MediaBox [0 0 612 792] >>\nendobj\n");

        appendTrailer(sb, pageId + 1, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createCyclicPageTree() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Pages /Kids [2 0 R] /Count 1 >>\nendobj\n");
        appendTrailer(sb, 4, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createEmptyPageTree() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n");
        appendTrailer(sb, 3, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createDuplicateKidsPageTree() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R 3 0 R 3 0 R] /Count 3 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n");
        appendTrailer(sb, 4, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createMissingMediaBoxTree() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n");
        appendTrailer(sb, 4, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createCustomBox(String boxCoords) {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox ").append(boxCoords).append(" >>\nendobj\n");
        appendTrailer(sb, 4, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createPageWithProperty(String prop) {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ").append(prop).append(" >>\nendobj\n");
        appendTrailer(sb, 4, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createTruncatedFlateStream() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n");
        sb.append("4 0 obj\n<< /Filter /FlateDecode /Length 6 >>\nstream\n\u0078\u009c\u0003\u0000\u0000\u0001\nendstream\nendobj\n");
        appendTrailer(sb, 5, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createCorruptAdler32Flate() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n");
        sb.append("4 0 obj\n<< /Filter /FlateDecode /Length 12 >>\nstream\n\u0078\u009c\u00cb\u00c9\u00c9\u0007\u0000\u00ff\u00ff\u00ff\u00ff\nendstream\nendobj\n");
        appendTrailer(sb, 5, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createContentStreamWithOps(String ops) {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n");
        sb.append("4 0 obj\n<< /Length ").append(ops.length()).append(" >>\nstream\n").append(ops).append("\nendstream\nendobj\n");
        sb.append("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");
        appendTrailer(sb, 6, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createFontMissingWidths() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n");
        sb.append("4 0 obj\n<< /Length 35 >>\nstream\nBT /F1 12 Tf (Missing Widths) Tj ET\nendstream\nendobj\n");
        sb.append("5 0 obj\n<< /Type /Font /Subtype /TrueType /BaseFont /Arial >>\nendobj\n");
        appendTrailer(sb, 6, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createFontZeroMatrix() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n");
        sb.append("4 0 obj\n<< /Length 30 >>\nstream\nBT /F1 12 Tf (Zero Matrix) Tj ET\nendstream\nendobj\n");
        sb.append("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FontMatrix [0 0 0 0 0 0] >>\nendobj\n");
        appendTrailer(sb, 6, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createBrokenToUnicodeCMap() {
        String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Custom-ToUnicode def\n"
                + "1 begincodespacerange <00> <FF> endcodespacerange\n"
                + "1 beginbfrange <00> <05> [<D800> <DBFF> <DC00>] endbfrange\n"
                + "endcmap CMapName currentdict /CMap defineresource pop end end\n";

        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n");
        sb.append("4 0 obj\n<< /Length 28 >>\nstream\nBT /F1 12 Tf (Broken CMap) Tj ET\nendstream\nendobj\n");
        sb.append("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 6 0 R >>\nendobj\n");
        sb.append("6 0 obj\n<< /Length ").append(cmap.length()).append(" >>\nstream\n").append(cmap).append("\nendstream\nendobj\n");
        appendTrailer(sb, 7, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createUnpairedSurrogatesPdf() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n");
        sb.append("4 0 obj\n<< /Length 40 >>\nstream\nBT /F1 12 Tf <FEFFD80000410042DFFF> Tj ET\nendstream\nendobj\n");
        sb.append("5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");
        appendTrailer(sb, 6, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createCyclicBookmarks() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n");
        sb.append("4 0 obj\n<< /Type /Outlines /First 5 0 R /Last 5 0 R /Count 1 >>\nendobj\n");
        sb.append("5 0 obj\n<< /Title (Loop 1) /Parent 4 0 R /Next 6 0 R /Dest [3 0 R /XYZ 0 792 0] >>\nendobj\n");
        sb.append("6 0 obj\n<< /Title (Loop 2) /Parent 4 0 R /Next 5 0 R /Dest [3 0 R /XYZ 0 792 0] >>\nendobj\n");
        appendTrailer(sb, 7, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createOutOfBoundsBookmark() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n");
        sb.append("4 0 obj\n<< /Type /Outlines /First 5 0 R /Last 5 0 R /Count 1 >>\nendobj\n");
        sb.append("5 0 obj\n<< /Title (Out of bounds) /Parent 4 0 R /Dest [99999 0 R /XYZ 0 792 0] >>\nendobj\n");
        appendTrailer(sb, 6, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createBrokenAppearanceAnnotation() {
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Annots [4 0 R] >>\nendobj\n");
        sb.append("4 0 obj\n<< /Type /Annot /Subtype /Widget /Rect [50 50 200 100] /AP << /N 999 0 R >> >>\nendobj\n");
        appendTrailer(sb, 5, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] createMalformedXmpPdf() {
        String badXmp = "<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?>\n<x:xmpmeta xmlns:x='adobe:ns:meta/'>\n<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>\n<rdf:Description>\n<dc:title><rdf:Alt><rdf:li xml:lang='x-default'>Bad < < / <xml> \u0000\u0001\u0002</dc:title>\n</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end='w'?>";

        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Metadata 4 0 R >>\nendobj\n");
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n");
        sb.append("4 0 obj\n<< /Type /Metadata /Subtype /XML /Length ").append(badXmp.length()).append(" >>\nstream\n").append(badXmp).append("\nendstream\nendobj\n");
        appendTrailer(sb, 5, 1);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void appendTrailer(StringBuilder sb, int totalObjects, int rootObj) {
        int xrefPos = sb.length();
        sb.append("xref\n0 ").append(totalObjects).append("\n");
        sb.append("0000000000 65535 f \n");
        for (int i = 1; i < totalObjects; i++) {
            sb.append(String.format("%010d 00000 n \n", 10 + (i * 50)));
        }
        sb.append("trailer\n<< /Root ").append(rootObj).append(" 0 R /Size ").append(totalObjects).append(" >>\n");
        sb.append("startxref\n").append(xrefPos).append("\n%%EOF\n");
    }
}
