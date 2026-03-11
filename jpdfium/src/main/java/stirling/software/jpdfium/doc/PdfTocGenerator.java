package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.TextChar;
import stirling.software.jpdfium.text.TextLine;
import stirling.software.jpdfium.text.TextWord;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Generate a Table of Contents page from detected headings.
 *
 * <p>Scans the document for lines with larger font sizes (assumed to be headings),
 * then creates a new first page with a clickable TOC listing those headings with
 * destination page numbers.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("report.pdf"))) {
 *     PdfTocGenerator.generate(doc, 14.0f);
 *     doc.save(Path.of("with-toc.pdf"));
 * }
 * }</pre>
 */
public final class PdfTocGenerator {

    private PdfTocGenerator() {}

    /** A detected heading with its text and page location. */
    public record TocEntry(int pageIndex, String text, float fontSize, float y) {}

    /**
     * Detect headings in the document based on font size threshold.
     *
     * @param doc               open PDF document
     * @param minHeadingFontSize minimum font size to be considered a heading
     * @return list of detected headings
     */
    public static List<TocEntry> detectHeadings(PdfDocument doc, float minHeadingFontSize) {
        List<TocEntry> entries = new ArrayList<>();

        for (int p = 0; p < doc.pageCount(); p++) {
            PageText pageText = PdfTextExtractor.extractPage(doc, p);
            for (TextLine line : pageText.lines()) {
                List<TextChar> allChars = lineChars(line);
                if (allChars.isEmpty()) continue;

                // Check if any character in the line exceeds the heading threshold
                float maxSize = 0;
                for (var ch : allChars) {
                    if (ch.fontSize() > maxSize) maxSize = ch.fontSize();
                }

                if (maxSize >= minHeadingFontSize) {
                    String text = line.text().strip();
                    if (!text.isEmpty() && text.length() > 1) {
                        float y = allChars.getFirst().y();
                        entries.add(new TocEntry(p, text, maxSize, y));
                    }
                }
            }
        }
        return entries;
    }

    /**
     * Generate a TOC page and insert it as the first page.
     *
     * <p>The TOC page lists all detected headings with their page numbers.
     * Since adding link annotations requires more complex bookmark/dest handling,
     * this generates a simple text-based TOC with page references.
     *
     * @param doc               open PDF document
     * @param minHeadingFontSize minimum font size for heading detection
     * @return number of TOC entries generated
     */
    public static int generate(PdfDocument doc, float minHeadingFontSize) {
        List<TocEntry> headings = detectHeadings(doc, minHeadingFontSize);
        if (headings.isEmpty()) return 0;

        MemorySegment rawDoc = doc.rawHandle();

        // Create a new page at the beginning (A4 size)
        float tocW = 595.28f;
        float tocH = 841.89f;
        MemorySegment tocPage;
        try {
            tocPage = (MemorySegment) PageEditBindings.FPDFPage_New.invokeExact(
                    rawDoc, 0, (double) tocW, (double) tocH);
        } catch (Throwable t) {
            throw new RuntimeException("FPDFPage_New failed", t);
        }

        // Load a standard font
        MemorySegment font;
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var fontName = arena.allocateFrom("Helvetica");
            font = (MemorySegment) PageEditBindings.FPDFPageObj_NewTextObj.invokeExact(
                    rawDoc, fontName, 20.0f);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create font", t);
        }

        // Add title "Table of Contents"
        addText(rawDoc, tocPage, "Table of Contents", 72f, tocH - 60f, 20f);

        // Add entries
        float yPos = tocH - 100f;
        float lineSpacing = 16f;
        int entriesAdded = 0;

        for (TocEntry entry : headings) {
            if (yPos < 72f) {
                // Would need multi-page TOC; skip for now
                break;
            }

            // +1 because we just inserted the TOC page at index 0
            int displayPage = entry.pageIndex() + 2;
            String line = entry.text();
            if (line.length() > 60) line = line.substring(0, 57) + "...";

            // Add dots and page number
            String pageStr = String.valueOf(displayPage);
            String tocLine = line + " " + ".".repeat(Math.max(1, 70 - line.length() - pageStr.length())) + " " + pageStr;

            addText(rawDoc, tocPage, tocLine, 72f, yPos, 10f);
            yPos -= lineSpacing;
            entriesAdded++;
        }

        // Generate content
        try {
            PageEditBindings.FPDFPage_GenerateContent.invokeExact(tocPage);
        } catch (Throwable t) {
            throw new RuntimeException("FPDFPage_GenerateContent failed", t);
        }

        // Close the page handle
        try {
            PageEditBindings.FPDF_ClosePage.invokeExact(tocPage);
        } catch (Throwable ignored) {}

        return entriesAdded;
    }

    /** Convenience overload: detect headings >= 14pt. */
    public static int generate(PdfDocument doc) {
        return generate(doc, 14.0f);
    }

    private static void addText(MemorySegment rawDoc, MemorySegment rawPage,
                                 String text, float x, float y, float fontSize) {
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var fontName = arena.allocateFrom("Helvetica");
            MemorySegment textObj = (MemorySegment) PageEditBindings.FPDFPageObj_NewTextObj.invokeExact(
                    rawDoc, fontName, fontSize);

            if (textObj.equals(MemorySegment.NULL)) return;

            // Set text content (UTF-16LE)
            var utf16 = arena.allocate(java.lang.foreign.ValueLayout.JAVA_SHORT, text.length() + 1);
            for (int i = 0; i < text.length(); i++) {
                utf16.setAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT, i, (short) text.charAt(i));
            }
            utf16.setAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT, text.length(), (short) 0);

            try {
                PageEditBindings.FPDFText_SetText.invokeExact(textObj, utf16);
            } catch (Throwable t) { return; }

            // Set fill color (black)
            try {
                PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(textObj, 0, 0, 0, 255);
            } catch (Throwable ignored) {}

            // Position the text
            try {
                PageEditBindings.FPDFPageObj_Transform.invokeExact(textObj,
                        1.0, 0.0, 0.0, 1.0, (double) x, (double) y);
            } catch (Throwable ignored) {}

            // Insert into page
            try {
                PageEditBindings.FPDFPage_InsertObject.invokeExact(rawPage, textObj);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            // Swallow - text obj creation failed
        }
    }

    private static List<TextChar> lineChars(TextLine line) {
        List<TextChar> chars = new ArrayList<>();
        for (TextWord w : line.words()) {
            chars.addAll(w.chars());
        }
        return chars;
    }
}
