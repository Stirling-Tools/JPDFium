package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.ImageObjBindings;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Report effective DPI for each embedded image and flag over-resolution images.
 *
 * <p>For each image object on each page, reports the native pixel dimensions,
 * the display size in points, and the effective DPI. Flags images above a threshold.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("photos.pdf"))) {
 *     var report = PdfImageDpiReport.analyze(doc);
 *     report.images().forEach(System.out::println);
 * }
 * }</pre>
 */
public final class PdfImageDpiReport {

    private PdfImageDpiReport() {}

    /** DPI information for a single embedded image. */
    public record ImageDpi(
            int pageIndex,
            int objectIndex,
            int pixelWidth,
            int pixelHeight,
            float displayWidthPt,
            float displayHeightPt,
            float effectiveDpiX,
            float effectiveDpiY,
            boolean overResolution
    ) {
        @Override
        public String toString() {
            return String.format("page=%d obj=%d native=%dx%d display=%.0fx%.0fpt dpi=%.0fx%.0f%s",
                    pageIndex, objectIndex, pixelWidth, pixelHeight,
                    displayWidthPt, displayHeightPt, effectiveDpiX, effectiveDpiY,
                    overResolution ? " [OVER-RES]" : "");
        }
    }

    /** Report of all images in a document. */
    public record DpiReport(List<ImageDpi> images, int totalImages, int overResImages,
                             float maxDpi) {}

    /**
     * Analyze all pages for image DPI.
     *
     * @param doc          document to analyze
     * @param dpiThreshold images above this DPI are flagged as over-resolution
     * @return DPI report
     */
    public static DpiReport analyze(PdfDocument doc, float dpiThreshold) {
        List<ImageDpi> images = new ArrayList<>();
        float maxDpi = 0;
        int overRes = 0;

        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();
                int objCount;
                try {
                    objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                } catch (Throwable t) { continue; }

                for (int i = 0; i < objCount; i++) {
                    MemorySegment obj;
                    try {
                        obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                    } catch (Throwable t) { continue; }
                    if (obj.equals(MemorySegment.NULL)) continue;

                    int type;
                    try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
                    catch (Throwable t) { continue; }
                    if (type != 3) continue; // 3 = IMAGE

                    try (Arena arena = Arena.ofConfined()) {
                        // Get pixel dimensions
                        MemorySegment widthSeg = arena.allocate(ValueLayout.JAVA_INT);
                        MemorySegment heightSeg = arena.allocate(ValueLayout.JAVA_INT);
                        int pixOk;
                        try {
                            pixOk = (int) ImageObjBindings.FPDFImageObj_GetImagePixelSize.invokeExact(
                                    obj, widthSeg, heightSeg);
                        } catch (Throwable t) { continue; }
                        if (pixOk == 0) continue;

                        int pixW = widthSeg.get(ValueLayout.JAVA_INT, 0);
                        int pixH = heightSeg.get(ValueLayout.JAVA_INT, 0);

                        // Get display bounds
                        MemorySegment left = arena.allocate(ValueLayout.JAVA_FLOAT);
                        MemorySegment bottom = arena.allocate(ValueLayout.JAVA_FLOAT);
                        MemorySegment right = arena.allocate(ValueLayout.JAVA_FLOAT);
                        MemorySegment top = arena.allocate(ValueLayout.JAVA_FLOAT);
                        try {
                            int bOk = (int) PageEditBindings.FPDFPageObj_GetBounds.invokeExact(
                                    obj, left, bottom, right, top);
                            if (bOk == 0) continue;
                        } catch (Throwable t) { continue; }

                        float dispW = right.get(ValueLayout.JAVA_FLOAT, 0) - left.get(ValueLayout.JAVA_FLOAT, 0);
                        float dispH = top.get(ValueLayout.JAVA_FLOAT, 0) - bottom.get(ValueLayout.JAVA_FLOAT, 0);
                        if (dispW <= 0 || dispH <= 0) continue;

                        // DPI = pixels / (display_points / 72)
                        float dpiX = pixW / (dispW / 72f);
                        float dpiY = pixH / (dispH / 72f);
                        boolean over = Math.max(dpiX, dpiY) > dpiThreshold;
                        if (over) overRes++;
                        maxDpi = Math.max(maxDpi, Math.max(dpiX, dpiY));

                        images.add(new ImageDpi(p, i, pixW, pixH, dispW, dispH, dpiX, dpiY, over));
                    }
                }
            }
        }

        return new DpiReport(Collections.unmodifiableList(images), images.size(), overRes, maxDpi);
    }

    /** Analyze with default 300 DPI threshold. */
    public static DpiReport analyze(PdfDocument doc) {
        return analyze(doc, 300f);
    }
}
