package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.TextPageBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Search for text and create highlight/underline/strikeout annotations on matches.
 *
 * <p>Non-destructive counterpart of redaction: finds text matches and marks them
 * with visual annotations. Annotations include proper QuadPoints for markup types.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     var result = PdfSearchHighlighter.highlight(doc, "confidential",
 *             AnnotationType.HIGHLIGHT, 0xFF, 0xFF, 0x00, 128);
 *     System.out.println("Highlighted " + result.totalMatches() + " matches");
 *     doc.save(Path.of("highlighted.pdf"));
 * }
 * }</pre>
 */
public final class PdfSearchHighlighter {

    private PdfSearchHighlighter() {}

    /** Search flags for PDFium. */
    public static final int FPDF_MATCHCASE     = 0x00000001;
    public static final int FPDF_MATCHWHOLEWORD = 0x00000002;

    /**
     * Result of a search-and-highlight operation.
     */
    public record HighlightResult(int totalMatches, List<PageResult> pageResults) {
        public record PageResult(int pageIndex, int matchCount, List<Rect> matchRects) {}
    }

    /**
     * Highlight all occurrences of a query string across all pages.
     *
     * @param doc   open PDF document
     * @param query text to search for
     * @param type  annotation type (HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY)
     * @param r     red (0-255)
     * @param g     green (0-255)
     * @param b     blue (0-255)
     * @param a     alpha (0-255)
     * @return result with match counts and positions
     */
    public static HighlightResult highlight(PdfDocument doc, String query,
                                             AnnotationType type,
                                             int r, int g, int b, int a) {
        return highlight(doc, query, type, r, g, b, a, false, false, null);
    }

    /**
     * Highlight occurrences with full options.
     *
     * @param doc           open PDF document
     * @param query         text to search for
     * @param type          annotation type (HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY)
     * @param r             red
     * @param g             green
     * @param b             blue
     * @param a             alpha
     * @param caseSensitive match case
     * @param wholeWord     match whole words only
     * @param contents      optional popup text for annotations
     * @return result with match counts and positions
     */
    public static HighlightResult highlight(PdfDocument doc, String query,
                                             AnnotationType type,
                                             int r, int g, int b, int a,
                                             boolean caseSensitive, boolean wholeWord,
                                             String contents) {
        List<HighlightResult.PageResult> pageResults = new ArrayList<>();
        int totalMatches = 0;

        for (int p = 0; p < doc.pageCount(); p++) {
            HighlightResult.PageResult pr = highlightPage(doc, p, query, type,
                    r, g, b, a, caseSensitive, wholeWord, contents);
            if (pr.matchCount() > 0) {
                pageResults.add(pr);
                totalMatches += pr.matchCount();
            }
        }

        return new HighlightResult(totalMatches, Collections.unmodifiableList(pageResults));
    }

    /**
     * Highlight occurrences on a single page.
     */
    public static HighlightResult.PageResult highlightPage(PdfDocument doc, int pageIndex,
                                                             String query, AnnotationType type,
                                                             int r, int g, int b, int a,
                                                             boolean caseSensitive, boolean wholeWord,
                                                             String contents) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            MemorySegment textPage;
            try {
                textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
            } catch (Throwable t) { throw new RuntimeException("FPDFText_LoadPage failed", t); }

            if (textPage.equals(MemorySegment.NULL)) {
                return new HighlightResult.PageResult(pageIndex, 0, List.of());
            }

            try {
                int flags = 0;
                if (caseSensitive) flags |= FPDF_MATCHCASE;
                if (wholeWord) flags |= FPDF_MATCHWHOLEWORD;

                List<Rect> matchRects = new ArrayList<>();

                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment queryStr = FfmHelper.toWideString(arena, query);

                    // Start search
                    MemorySegment searchHandle;
                    try {
                        searchHandle = (MemorySegment) TextPageBindings.FPDFText_FindStart.invokeExact(
                                textPage, queryStr, flags, 0);
                    } catch (Throwable t) { throw new RuntimeException("FPDFText_FindStart failed", t); }

                    if (searchHandle.equals(MemorySegment.NULL)) {
                        return new HighlightResult.PageResult(pageIndex, 0, List.of());
                    }

                    try {
                        while (true) {
                            int found;
                            try {
                                found = (int) TextPageBindings.FPDFText_FindNext.invokeExact(searchHandle);
                            } catch (Throwable t) { break; }
                            if (found == 0) break;

                            int charIndex;
                            try {
                                charIndex = (int) TextPageBindings.FPDFText_GetSchResultIndex.invokeExact(searchHandle);
                            } catch (Throwable t) { continue; }

                            int charCount;
                            try {
                                charCount = (int) TextPageBindings.FPDFText_GetSchCount.invokeExact(searchHandle);
                            } catch (Throwable t) { continue; }

                            // Get rectangles covering the matched character range
                            List<Rect> rects = getRectsForRange(textPage, charIndex, charCount, arena);

                            for (Rect rect : rects) {
                                createMarkupAnnotation(rawPage, rect, type, r, g, b, a, contents);
                                matchRects.add(rect);
                            }
                        }
                    } finally {
                        try { TextPageBindings.FPDFText_FindClose.invokeExact(searchHandle); }
                        catch (Throwable ignored) {}
                    }
                }

                return new HighlightResult.PageResult(pageIndex, matchRects.size(),
                        Collections.unmodifiableList(matchRects));
            } finally {
                try { TextPageBindings.FPDFText_ClosePage.invokeExact(textPage); }
                catch (Throwable ignored) {}
            }
        }
    }

    private static List<Rect> getRectsForRange(MemorySegment textPage, int startIndex,
                                                 int charCount, Arena arena) {
        int rectCount;
        try {
            rectCount = (int) TextPageBindings.FPDFText_CountRects.invokeExact(
                    textPage, startIndex, charCount);
        } catch (Throwable t) { return List.of(); }

        if (rectCount <= 0) return List.of();

        List<Rect> rects = new ArrayList<>(rectCount);
        MemorySegment left = arena.allocate(ValueLayout.JAVA_DOUBLE);
        MemorySegment top = arena.allocate(ValueLayout.JAVA_DOUBLE);
        MemorySegment right = arena.allocate(ValueLayout.JAVA_DOUBLE);
        MemorySegment bottom = arena.allocate(ValueLayout.JAVA_DOUBLE);

        for (int i = 0; i < rectCount; i++) {
            int ok;
            try {
                ok = (int) TextPageBindings.FPDFText_GetRect.invokeExact(
                        textPage, i, left, top, right, bottom);
            } catch (Throwable t) { continue; }
            if (ok == 0) continue;

            float l = (float) left.get(ValueLayout.JAVA_DOUBLE, 0);
            float t2 = (float) top.get(ValueLayout.JAVA_DOUBLE, 0);
            float r = (float) right.get(ValueLayout.JAVA_DOUBLE, 0);
            float b = (float) bottom.get(ValueLayout.JAVA_DOUBLE, 0);

            rects.add(new Rect(l, b, r - l, t2 - b));
        }
        return rects;
    }

    private static void createMarkupAnnotation(MemorySegment rawPage, Rect rect,
                                                 AnnotationType type,
                                                 int r, int g, int b, int a,
                                                 String contents) {
        PdfAnnotationBuilder builder = PdfAnnotationBuilder.on(rawPage)
                .type(type)
                .rect(rect)
                .color(r, g, b, a);

        if (contents != null) {
            builder.contents(contents);
        }

        builder.build();
    }
}
