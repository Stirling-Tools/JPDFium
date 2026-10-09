package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Set;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Converts page object fill/stroke colors between color spaces (RGB, grayscale) using
 * luminance-preserving conversion.
 */
public final class PdfColorConverter {

    private PdfColorConverter() {}

    /**
     * Target color space for conversion. CMYK is declared but unsupported:
     * the PDFium object color API only emits DeviceRGB.
     */
    public enum ColorSpace {
        GRAYSCALE,
        RGB,
        CMYK
    }

    /**
     * True if the native conversion path can emit the given target.
     */
    public static boolean supportsColorSpace(ColorSpace target) {
        return target == ColorSpace.GRAYSCALE || target == ColorSpace.RGB;
    }

    /**
     * Color spaces this converter can currently emit.
     */
    public static Set<ColorSpace> supportedColorSpaces() {
        return EnumSet.of(ColorSpace.GRAYSCALE, ColorSpace.RGB);
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
     * Rewrite every fill/stroke color on all pages as DeviceRGB.
     *
     * @param doc the document to modify (in place)
     * @return number of objects whose colors were rewritten
     */
    public static int toRgb(PdfDocument doc) {
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                total += convertPage(page.rawHandle(),
                        ColorConvertOptions.builder()
                                .targetColorSpace(ColorSpace.RGB)
                                .preserveBlack(false)
                                .build());
            }
        }
        return total;
    }

    /**
     * Normalize specific pages to DeviceRGB.
     *
     * @param doc         the document to modify (in place)
     * @param pageIndices set of 0-based page indices to convert
     * @return number of objects whose colors were rewritten
     */
    public static int toRgb(PdfDocument doc, Set<Integer> pageIndices) {
        int total = 0;
        for (int i : pageIndices) {
            if (i >= 0 && i < doc.pageCount()) {
                try (PdfPage page = doc.page(i)) {
                    total += convertPage(page.rawHandle(), ColorConvertOptions.builder()
                            .targetColorSpace(ColorSpace.RGB)
                            .preserveBlack(false)
                            .build());
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
        ColorSpace target = options.targetColorSpace();
        if (!supportsColorSpace(target)) {
            throw new JPDFiumException("Unsupported color conversion target: " + target
                    + " (PDFium object color API emits DeviceRGB only)");
        }
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
        ColorSpace target = options.targetColorSpace();
        if (!supportsColorSpace(target)) {
            throw new JPDFiumException("Unsupported color conversion target: " + target
                    + " (PDFium object color API emits DeviceRGB only)");
        }
        int count;
        try {
            count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return 0;
        }
        if (count <= 0) return 0;

        int converted = 0;
        boolean changed = false;

        // One arena and four int slots are reused for every object on the page.
        // Allocating them per fill/stroke call dominated the allocation profile.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment r = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment g = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment a = arena.allocate(ValueLayout.JAVA_INT);

            for (int i = 0; i < count; i++) {
                MemorySegment obj;
                try {
                    obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    continue;
                }
                if (obj.equals(MemorySegment.NULL)) continue;

                int type;
                try {
                    type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    continue;
                }

                // Type 1 = TEXT, 2 = PATH, 3 = IMAGE.
                boolean isText = (type == 1);
                boolean isPath = (type == 2);
                if (isText && !options.convertText()) continue;
                if (isPath && !options.convertVectors()) continue;
                if (type == 3 && !options.convertImages()) continue;

                if (isText || isPath) {
                    if (convertColor(obj, target, options.preserveBlack(), r, g, b, a, true)) {
                        changed = true;
                        converted++;
                    }
                    if (convertColor(obj, target, options.preserveBlack(), r, g, b, a, false)) {
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            try {
                PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFPage_GenerateContent failed", t);
            }
        }
        return converted;
    }

    /** Reads, remaps and writes one fill or stroke colour using the shared slots. */
    private static boolean convertColor(MemorySegment obj, ColorSpace target, boolean preserveBlack,
                                        MemorySegment r, MemorySegment g, MemorySegment b, MemorySegment a,
                                        boolean fill) {
        try {
            int ok = fill
                    ? (int) PageEditBindings.FPDFPageObj_GetFillColor.invokeExact(obj, r, g, b, a)
                    : (int) PageEditBindings.FPDFPageObj_GetStrokeColor.invokeExact(obj, r, g, b, a);
            if (ok == 0) return false;

            int ri = r.get(ValueLayout.JAVA_INT, 0);
            int gi = g.get(ValueLayout.JAVA_INT, 0);
            int bi = b.get(ValueLayout.JAVA_INT, 0);
            int ai = a.get(ValueLayout.JAVA_INT, 0);
            if (preserveBlack && ri == 0 && gi == 0 && bi == 0) return false;

            int tr = ri;
            int tg = gi;
            int tb = bi;
            if (target == ColorSpace.GRAYSCALE) {
                tr = tg = tb = toGray(ri, gi, bi);
            }

            int setOk = fill
                    ? (int) PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(obj, tr, tg, tb, ai)
                    : (int) PageEditBindings.FPDFPageObj_SetStrokeColor.invokeExact(obj, tr, tg, tb, ai);
            return setOk != 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    /** RGB to grayscale using ITU-R BT.709 luminance coefficients. */
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
