package stirling.software.jpdfium.doc;

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
 * Generates intentionally damaged PDFs for repair testing.
 *
 * <p>Each method produces a {@code byte[]} containing a PDF with a specific
 * class of damage, inspired by real-world corruption patterns seen in
 * PDF.js bug tracker, Ghostscript test suite, and qpdf issue reports.
 *
 * <p>Damage categories:
 * <ol>
 *   <li><b>Structural</b> - xref table corruption, bad startxref, missing trailer</li>
 *   <li><b>Truncation</b> - incomplete downloads, mid-stream cut</li>
 *   <li><b>Syntax</b> - missing endobj, broken object references</li>
 *   <li><b>Stream</b> - wrong stream length, corrupted stream data</li>
 * </ol>
 *
 * <p>All PDFs are generated from scratch via Apache PDFBox (Apache 2.0 license),
 * so there are zero external corpus licensing concerns.
 */
public final class RepairCorpus {

    private RepairCorpus() {}

    /**
     * Returns all damage specimens as a name→bytes map.
     * Names follow the pattern {@code damage-<category>-<variant>}.
     */
    public static Map<String, byte[]> all() throws IOException {
        byte[] validPdf = validMultiPage();
        Map<String, byte[]> corpus = new LinkedHashMap<>();

        corpus.put("damage-xref-corrupted", corruptXref(validPdf));
        corpus.put("damage-xref-zeroed", zeroXref(validPdf));
        corpus.put("damage-startxref-wrong", wrongStartxref(validPdf));
        corpus.put("damage-truncated-75pct", truncate(validPdf, 0.75));
        corpus.put("damage-truncated-50pct", truncate(validPdf, 0.50));
        corpus.put("damage-truncated-90pct", truncate(validPdf, 0.90));
        corpus.put("damage-missing-eof", stripEof(validPdf));
        corpus.put("damage-trailer-missing", stripTrailer(validPdf));
        corpus.put("damage-stream-length-wrong", wrongStreamLength(validPdf));
        corpus.put("damage-endobj-missing", stripEndobj(validPdf));
        corpus.put("damage-header-garbage", garbageHeader(validPdf));
        corpus.put("damage-null-bytes-injected", injectNullBytes(validPdf));

        return corpus;
    }

    /** A valid 3-page PDF with text content, created via PDFBox. */
    public static byte[] validMultiPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= 3; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Repair test page " + i + " of 3");
                    cs.newLineAtOffset(0, -20);
                    cs.showText("Lorem ipsum dolor sit amet, consectetur adipiscing elit.");
                    cs.newLineAtOffset(0, -20);
                    cs.showText("The quick brown fox jumps over the lazy dog. 0123456789");
                    cs.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    

    /** Replace xref table bytes with random ASCII, keeping the rest of the file intact. */
    static byte[] corruptXref(byte[] pdf) {
        byte[] copy = pdf.clone();
        String s = new String(copy, StandardCharsets.ISO_8859_1);
        int xrefPos = s.lastIndexOf("xref");
        if (xrefPos < 0) return copy; // xref-stream PDF, skip
        int trailerPos = s.indexOf("trailer", xrefPos);
        if (trailerPos < 0) trailerPos = copy.length;
        // Overwrite xref entries (but not the "xref" keyword itself)
        for (int i = xrefPos + 5; i < trailerPos && i < copy.length; i++) {
            copy[i] = (byte) ('A' + (i % 26));
        }
        return copy;
    }

    /** Zero out the xref table entries. */
    static byte[] zeroXref(byte[] pdf) {
        byte[] copy = pdf.clone();
        String s = new String(copy, StandardCharsets.ISO_8859_1);
        int xrefPos = s.lastIndexOf("xref");
        if (xrefPos < 0) return copy;
        int trailerPos = s.indexOf("trailer", xrefPos);
        if (trailerPos < 0) trailerPos = copy.length;
        Arrays.fill(copy, xrefPos + 5, trailerPos, (byte) '0');
        return copy;
    }

    /** Change the startxref offset to point to a wrong location. */
    static byte[] wrongStartxref(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int startxrefPos = s.lastIndexOf("startxref");
        if (startxrefPos < 0) return pdf;
        // Replace the number after "startxref\n" with a garbage offset
        String before = s.substring(0, startxrefPos);
        return (before + "startxref\n999999\n%%EOF").getBytes(StandardCharsets.ISO_8859_1);
    }

    

    /** Truncate the PDF at the given ratio (0.0-1.0). */
    static byte[] truncate(byte[] pdf, double ratio) {
        int len = Math.max(32, (int) (pdf.length * ratio));
        return Arrays.copyOf(pdf, len);
    }

    /** Remove the %%EOF marker. */
    static byte[] stripEof(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int eofPos = s.lastIndexOf("%%EOF");
        if (eofPos < 0) return pdf;
        return s.substring(0, eofPos).getBytes(StandardCharsets.ISO_8859_1);
    }

    

    /** Remove the trailer dictionary. */
    static byte[] stripTrailer(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int trailerPos = s.lastIndexOf("trailer");
        if (trailerPos < 0) return pdf;
        int startxrefPos = s.lastIndexOf("startxref");
        if (startxrefPos < 0) return pdf;
        // Remove the trailer but keep startxref and %%EOF
        String before = s.substring(0, trailerPos);
        String after = s.substring(startxrefPos);
        return (before + after).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Remove endobj markers from the first two objects. */
    static byte[] stripEndobj(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        // Remove first two occurrences of "endobj"
        int count = 0;
        StringBuilder sb = new StringBuilder(s);
        int idx;
        while (count < 2 && (idx = sb.indexOf("endobj")) >= 0) {
            sb.delete(idx, idx + 6);
            count++;
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    

    /** Change the /Length value of the first content stream to be wrong. */
    static byte[] wrongStreamLength(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        // Find first "/Length " and change the number
        int pos = s.indexOf("/Length ");
        if (pos < 0) return pdf;
        int numStart = pos + 8;
        int numEnd = numStart;
        while (numEnd < s.length() && Character.isDigit(s.charAt(numEnd))) numEnd++;
        String before = s.substring(0, numStart);
        String after = s.substring(numEnd);
        return (before + "99999" + after).getBytes(StandardCharsets.ISO_8859_1);
    }

    

    /** Prepend garbage bytes before the %PDF header. */
    static byte[] garbageHeader(byte[] pdf) {
        byte[] garbage = "GARBAGE BYTES BEFORE HEADER\n\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] result = new byte[garbage.length + pdf.length];
        System.arraycopy(garbage, 0, result, 0, garbage.length);
        System.arraycopy(pdf, 0, result, garbage.length, pdf.length);
        return result;
    }

    /** Inject null bytes at regular intervals throughout the file. */
    static byte[] injectNullBytes(byte[] pdf) {
        byte[] copy = pdf.clone();
        // Inject nulls in the middle 50% of the file, every ~100 bytes
        int start = copy.length / 4;
        int end = start + copy.length / 2;
        for (int i = start; i < end; i += 97) {
            copy[i] = 0;
        }
        return copy;
    }
}
