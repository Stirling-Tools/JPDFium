package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;

/**
 * Convert page object colors between color spaces (RGB → grayscale, etc.).
 *
 * <p>Walks all page objects (text, paths, images) and converts fill/stroke
 * colors to the target color space using luminance-preserving conversion.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("color.pdf"))) {
 *     PdfColorConverter.toGrayscale(doc);
 *     doc.save(Path.of("grayscale.pdf"));
 * }
 * }</pre>
 */
public final class PdfColorConverter {

    private PdfColorConverter() {}

    /**
     * Target color space for conversion.
     */
    public enum ColorSpace {
        GRAYSCALE
    }

    /**
     * Convert all pages to grayscale.
     *
     * @param doc the document to modify (in place)
     * @return number of objects whose colors were converted
     */
    public static int toGrayscale(PdfDocument doc) {
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                total += convertPage(page.rawHandle(), ColorSpace.GRAYSCALE);
            }
        }
        return total;
    }

    /**
     * Convert specific pages to grayscale.
     *
     * @param doc         the document to modify (in place)
     * @param pageIndices set of 0-based page indices to convert
     * @return number of objects whose colors were converted
     */
    public static int toGrayscale(PdfDocument doc, Set<Integer> pageIndices) {
        int total = 0;
        for (int i : pageIndices) {
            if (i >= 0 && i < doc.pageCount()) {
                try (PdfPage page = doc.page(i)) {
                    total += convertPage(page.rawHandle(), ColorSpace.GRAYSCALE);
                }
            }
        }
        return total;
    }

    /**
     * Convert colors on a single page using the specified options.
     *
     * @param doc     the document to modify (in place)
     * @param options conversion options
     * @return number of objects whose colors were converted
     */
    public static int convert(PdfDocument doc, ColorConvertOptions options) {
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                total += convertPage(page.rawHandle(), options);
            }
        }
        return total;
    }

    private static int convertPage(MemorySegment rawPage, ColorSpace target) {
        return convertPage(rawPage, ColorConvertOptions.builder()
                .targetColorSpace(target)
                .build());
    }

    private static int convertPage(MemorySegment rawPage, ColorConvertOptions options) {
        int count;
        try {
            count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) { return 0; }

        int converted = 0;
        boolean changed = false;

        for (int i = 0; i < count; i++) {
            MemorySegment obj;
            try {
                obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
            } catch (Throwable t) { continue; }
            if (obj.equals(MemorySegment.NULL)) continue;

            int type;
            try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
            catch (Throwable t) { continue; }

            // Type 1 = TEXT, 2 = PATH, 3 = IMAGE
            boolean isText = (type == 1);
            boolean isPath = (type == 2);

            if (isText && !options.convertText()) continue;
            if (isPath && !options.convertVectors()) continue;
            if (type == 3 && !options.convertImages()) continue;

            // Convert fill color
            if (isText || isPath) {
                if (convertFillColor(obj, options)) {
                    changed = true;
                    converted++;
                }
                if (convertStrokeColor(obj, options)) {
                    changed = true;
                }
            }
        }

        if (changed) {
            try { int gcOk = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage); }
            catch (Throwable t) { throw new RuntimeException("FPDFPage_GenerateContent failed", t); }
        }
        return converted;
    }

    private static boolean convertFillColor(MemorySegment obj, ColorConvertOptions options) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment r = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment g = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment a = arena.allocate(ValueLayout.JAVA_INT);

            int ok = (int) PageEditBindings.FPDFPageObj_GetFillColor.invokeExact(obj, r, g, b, a);
            if (ok == 0) return false;

            int ri = r.get(ValueLayout.JAVA_INT, 0);
            int gi = g.get(ValueLayout.JAVA_INT, 0);
            int bi = b.get(ValueLayout.JAVA_INT, 0);
            int ai = a.get(ValueLayout.JAVA_INT, 0);

            if (options.preserveBlack() && ri == 0 && gi == 0 && bi == 0) return false;

            int gray = toGray(ri, gi, bi);
            int setOk = (int) PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(
                    obj, gray, gray, gray, ai);
            return setOk != 0;
        } catch (Throwable t) { return false; }
    }

    private static boolean convertStrokeColor(MemorySegment obj, ColorConvertOptions options) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment r = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment g = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment a = arena.allocate(ValueLayout.JAVA_INT);

            int ok = (int) PageEditBindings.FPDFPageObj_GetStrokeColor.invokeExact(obj, r, g, b, a);
            if (ok == 0) return false;

            int ri = r.get(ValueLayout.JAVA_INT, 0);
            int gi = g.get(ValueLayout.JAVA_INT, 0);
            int bi = b.get(ValueLayout.JAVA_INT, 0);
            int ai = a.get(ValueLayout.JAVA_INT, 0);

            if (options.preserveBlack() && ri == 0 && gi == 0 && bi == 0) return false;

            int gray = toGray(ri, gi, bi);
            int setOk = (int) PageEditBindings.FPDFPageObj_SetStrokeColor.invokeExact(
                    obj, gray, gray, gray, ai);
            return setOk != 0;
        } catch (Throwable t) { return false; }
    }

    /**
     * Convert RGB to grayscale using ITU-R BT.709 luminance coefficients.
     */
    private static int toGray(int r, int g, int b) {
        return Math.clamp(Math.round(0.2126f * r + 0.7152f * g + 0.0722f * b), 0, 255);
    }

    /**
     * Options for color conversion (builder pattern).
     */
    public static final class ColorConvertOptions {
        private final ColorSpace targetColorSpace;
        private final boolean convertImages;
        private final boolean convertVectors;
        private final boolean convertText;
        private final boolean preserveBlack;

        private ColorConvertOptions(Builder b) {
            this.targetColorSpace = b.targetColorSpace;
            this.convertImages = b.convertImages;
            this.convertVectors = b.convertVectors;
            this.convertText = b.convertText;
            this.preserveBlack = b.preserveBlack;
        }

        public ColorSpace targetColorSpace() { return targetColorSpace; }
        public boolean convertImages() { return convertImages; }
        public boolean convertVectors() { return convertVectors; }
        public boolean convertText() { return convertText; }
        public boolean preserveBlack() { return preserveBlack; }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private ColorSpace targetColorSpace = ColorSpace.GRAYSCALE;
            private boolean convertImages = true;
            private boolean convertVectors = true;
            private boolean convertText = true;
            private boolean preserveBlack = true;

            private Builder() {}

            public Builder targetColorSpace(ColorSpace cs) { this.targetColorSpace = cs; return this; }
            public Builder convertImages(boolean v) { this.convertImages = v; return this; }
            public Builder convertVectors(boolean v) { this.convertVectors = v; return this; }
            public Builder convertText(boolean v) { this.convertText = v; return this; }
            public Builder preserveBlack(boolean v) { this.preserveBlack = v; return this; }

            public ColorConvertOptions build() { return new ColorConvertOptions(this); }
        }
    }
}
