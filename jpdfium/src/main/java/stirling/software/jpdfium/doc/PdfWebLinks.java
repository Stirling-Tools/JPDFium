package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.TextPageBindings;
import stirling.software.jpdfium.panama.WebLinkBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detect, add, and remove web links on PDF pages.
 *
 * <p>
 * Text-based URL detection uses PDFium's FPDFLink_LoadWebLinks which finds
 * URLs in the text layer. Link annotations can be added and removed using
 * the annotation API.
 */
public final class PdfWebLinks {

    private PdfWebLinks() {
    }

    /**
     * Extract all web links from a page's text layer.
     *
     * @param rawPage   raw FPDF_PAGE segment
     * @param pageIndex 0-based page index (for reporting)
     * @return detected web links with URLs and bounding rectangles
     */
    public static List<WebLink> extract(MemorySegment rawPage, int pageIndex) {
        MemorySegment textPage;
        try {
            textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        if (textPage.equals(MemorySegment.NULL))
            return Collections.emptyList();

        try {
            MemorySegment webLinks;
            try {
                webLinks = (MemorySegment) WebLinkBindings.FPDFLink_LoadWebLinks.invokeExact(textPage);
            } catch (Throwable t) {
                return Collections.emptyList();
            }
            if (webLinks.equals(MemorySegment.NULL))
                return Collections.emptyList();

            try {
                int count;
                try {
                    count = (int) WebLinkBindings.FPDFLink_CountWebLinks.invokeExact(webLinks);
                } catch (Throwable t) {
                    return Collections.emptyList();
                }

                List<WebLink> result = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String url = getUrl(webLinks, i);
                    int[] charRange = getTextRange(webLinks, i);
                    List<Rect> rects = getRects(webLinks, i);
                    result.add(new WebLink(pageIndex, url, charRange[0], charRange[1], rects));
                }
                return Collections.unmodifiableList(result);
            } finally {
                try {
                    WebLinkBindings.FPDFLink_CloseWebLinks.invokeExact(webLinks);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            try {
                TextPageBindings.FPDFText_ClosePage.invokeExact(textPage);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Add a link annotation with the given URI to a page.
     *
     * @param rawPage raw FPDF_PAGE
     * @param rect    rectangle where the link is placed (page coordinates)
     * @param uri     target URL (e.g. "https://example.com")
     * @return annotation index, or -1 on failure
     */
    public static int addLink(MemorySegment rawPage, Rect rect, String uri) {
        return addLink(rawPage, rect, uri, null);
    }

    /**
     * Add a link annotation with the given URI to a page, with customizable
     * appearance.
     *
     * <p>By default, creates a visible hyperlink with blue underline styling
     * (similar to browser hyperlinks). Use the builderConsumer to customize
     * colors, border style, width, and other annotation properties.
     *
     * @param rawPage         raw FPDF_PAGE
     * @param rect            rectangle where the link is placed (page coordinates)
     * @param uri             target URL
     * @param builderConsumer optional consumer to customize the link annotation
     * @return annotation index, or -1 on failure
     */
    public static int addLink(MemorySegment rawPage, Rect rect, String uri,
            java.util.function.UnaryOperator<PdfAnnotationBuilder> builderConsumer) {
        PdfAnnotationBuilder builder = PdfAnnotationBuilder.on(rawPage)
                .type(AnnotationType.LINK)
                .rect(rect)
                .uri(uri)
                .color(0, 0, 255)           // blue color (standard hyperlink blue)
                .borderWidth(1f)            // visible border width
                .borderStyle(5)             // underline style (5 = underline)
                .generateAppearance();      // generate appearance stream for visibility

        if (builderConsumer != null) {
            builder = builderConsumer.apply(builder);
        }

        return builder.build();
    }

    /**
     * Remove all link annotations from a page.
     *
     * @param rawPage raw FPDF_PAGE
     * @return number of link annotations removed
     */
    public static int removeAllLinks(MemorySegment rawPage) {
        int count;
        try {
            count = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
        } catch (Throwable t) {
            return 0;
        }

        int removed = 0;
        // Iterate backwards to avoid index shifting
        for (int i = count - 1; i >= 0; i--) {
            MemorySegment annot;
            try {
                annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, i);
            } catch (Throwable t) {
                continue;
            }
            if (annot.equals(MemorySegment.NULL))
                continue;

            try {
                int subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
                if (subtype == AnnotationType.LINK.code()) {
                    try {
                        AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot);
                    } catch (Throwable ignored) {
                    }
                    int ok = (int) AnnotationBindings.FPDFPage_RemoveAnnot.invokeExact(rawPage, i);
                    if (ok != 0)
                        removed++;
                    continue; // annot already closed
                }
            } catch (Throwable ignored) {
            }

            try {
                AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot);
            } catch (Throwable ignored) {
            }
        }
        return removed;
    }

    /**
     * Count link annotations on a page.
     *
     * @param rawPage raw FPDF_PAGE
     * @return number of link annotations
     */
    public static int countLinkAnnotations(MemorySegment rawPage) {
        int count;
        try {
            count = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
        } catch (Throwable t) {
            return 0;
        }

        int linkCount = 0;
        for (int i = 0; i < count; i++) {
            MemorySegment annot;
            try {
                annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, i);
            } catch (Throwable t) {
                continue;
            }
            if (annot.equals(MemorySegment.NULL))
                continue;

            try {
                int subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
                if (subtype == AnnotationType.LINK.code())
                    linkCount++;
            } catch (Throwable ignored) {
            } finally {
                try {
                    AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot);
                } catch (Throwable ignored) {
                }
            }
        }
        return linkCount;
    }

    private static String getUrl(MemorySegment webLinks, int index) {
        try (Arena arena = Arena.ofConfined()) {
            int charCount = (int) WebLinkBindings.FPDFLink_GetURL.invokeExact(
                    webLinks, index, MemorySegment.NULL, 0);
            if (charCount <= 0)
                return "";
            MemorySegment buf = arena.allocate((long) charCount * 2);
            WebLinkBindings.FPDFLink_GetURL.invokeExact(webLinks, index, buf, charCount);
            return FfmHelper.fromWideString(buf, (long) charCount * 2);
        } catch (Throwable t) {
            return "";
        }
    }

    private static int[] getTextRange(MemorySegment webLinks, int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment startIdx = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) WebLinkBindings.FPDFLink_GetTextRange.invokeExact(
                    webLinks, index, startIdx, count);
            if (ok == 0)
                return new int[] { 0, 0 };
            return new int[] {
                    startIdx.get(ValueLayout.JAVA_INT, 0),
                    startIdx.get(ValueLayout.JAVA_INT, 0) + count.get(ValueLayout.JAVA_INT, 0)
            };
        } catch (Throwable t) {
            return new int[] { 0, 0 };
        }
    }

    private static List<Rect> getRects(MemorySegment webLinks, int index) {
        int rectCount;
        try {
            rectCount = (int) WebLinkBindings.FPDFLink_CountRects.invokeExact(webLinks, index);
        } catch (Throwable t) {
            return Collections.emptyList();
        }

        List<Rect> rects = new ArrayList<>(rectCount);
        for (int r = 0; r < rectCount; r++) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment left = arena.allocate(ValueLayout.JAVA_DOUBLE);
                MemorySegment top = arena.allocate(ValueLayout.JAVA_DOUBLE);
                MemorySegment right = arena.allocate(ValueLayout.JAVA_DOUBLE);
                MemorySegment bottom = arena.allocate(ValueLayout.JAVA_DOUBLE);
                int ok = (int) WebLinkBindings.FPDFLink_GetRect.invokeExact(
                        webLinks, index, r, left, top, right, bottom);
                if (ok != 0) {
                    float l = (float) left.get(ValueLayout.JAVA_DOUBLE, 0);
                    float b = (float) bottom.get(ValueLayout.JAVA_DOUBLE, 0);
                    float ri = (float) right.get(ValueLayout.JAVA_DOUBLE, 0);
                    float t = (float) top.get(ValueLayout.JAVA_DOUBLE, 0);
                    rects.add(new Rect(l, b, ri - l, t - b));
                }
            } catch (Throwable ignored) {
            }
        }
        return rects;
    }
}
