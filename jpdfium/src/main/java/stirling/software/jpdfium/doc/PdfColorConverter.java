package stirling.software.jpdfium.doc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Set;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.ColorBindings;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PageEditBindings;

/**
 * Converts page object fill/stroke colors between grayscale, RGB and DeviceCMYK.
 * CMYK needs the JPDFium PDFium patch ({@link #supportsColorSpace(ColorSpace)}).
 */
public final class PdfColorConverter {

    private PdfColorConverter() {}

    /** Target color space for conversion. */
    public enum ColorSpace {
        GRAYSCALE,
        RGB,
        CMYK
    }

    /** True if the native conversion path can emit the given target. */
    public static boolean supportsColorSpace(ColorSpace target) {
        if (target == ColorSpace.GRAYSCALE || target == ColorSpace.RGB) return true;
        return target == ColorSpace.CMYK && ColorBindings.vectorCmykAvailable();
    }

    /**
     * Color spaces this converter can currently emit.
     */
    public static Set<ColorSpace> supportedColorSpaces() {
        EnumSet<ColorSpace> spaces = EnumSet.of(ColorSpace.GRAYSCALE, ColorSpace.RGB);
        if (ColorBindings.vectorCmykAvailable()) spaces.add(ColorSpace.CMYK);
        return spaces;
    }

    /** True if image XObjects can be re-encoded as DeviceCMYK JPEGs in this build. */
    public static boolean supportsImageConversion() {
        return ColorBindings.imageConversionAvailable();
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

    /** Convert every page to DeviceCMYK (throws when the CMYK setters are absent). */
    public static int toCmyk(PdfDocument doc) {
        return convert(doc, ColorConvertOptions.builder()
                .targetColorSpace(ColorSpace.CMYK)
                .build());
    }

    /** Convert specific pages to DeviceCMYK (throws when the setters are absent). */
    public static int toCmyk(PdfDocument doc, Set<Integer> pageIndices) {
        ColorConvertOptions options = ColorConvertOptions.builder()
                .targetColorSpace(ColorSpace.CMYK)
                .build();
        requireCmykSupport(options);
        int total = 0;
        for (int i : pageIndices) {
            if (i >= 0 && i < doc.pageCount()) {
                total += convertDocumentPage(doc, i, options, cmykTransform(options));
            }
        }
        return total;
    }

    /**
     * Re-encode every convertible image XObject on every page as a DeviceCMYK JPEG.
     * Returns the count, or {@code -1} when the encoder is unavailable or a page failed.
     */
    public static int convertImagesToCmyk(PdfDocument doc, int quality) {
        if (!ColorBindings.imageConversionAvailable()) return -1;
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            int n = ColorBindings.pageImagesToCmyk(doc.nativeHandle(), i, quality, 0);
            if (n < 0) return -1;
            total += n;
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
        requireCmykSupport(options);
        IccCmykTransform xform = cmykTransform(options);
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            total += convertDocumentPage(doc, i, options, xform);
        }
        return total;
    }

    /** Reject CMYK requests the loaded library cannot satisfy, with an actionable message. */
    private static void requireCmykSupport(ColorConvertOptions options) {
        if (options.targetColorSpace() == ColorSpace.CMYK
                && !ColorBindings.vectorCmykAvailable()) {
            throw new JPDFiumException("Unsupported color conversion target: CMYK"
                    + " (this PDFium build has no CMYK page-object colour API;"
                    + " PDFium must be rebuilt with the JPDFium colour patch and the"
                    + " prebuilt pin bumped. Image-only CMYK conversion is available"
                    + " now via PdfColorConverter.convertImagesToCmyk)");
        }
        if (options.targetColorSpace() == ColorSpace.CMYK
                && options.convertImages() && options.strictImages()
                && !ColorBindings.imageConversionAvailable()) {
            throw new JPDFiumException("Unsupported: strict CMYK image conversion was requested"
                    + " but this PDFium build has no CMYK JPEG image API"
                    + " (JPEG embedding extension missing). Rebuild the natives, or set"
                    + " strictImages(false) to convert vector colours only.");
        }
        if (!supportsColorSpace(options.targetColorSpace())) {
            throw new JPDFiumException("Unsupported color conversion target: "
                    + options.targetColorSpace());
        }
    }

    private static IccCmykTransform cmykTransform(ColorConvertOptions options) {
        if (options.targetColorSpace() != ColorSpace.CMYK) return null;
        byte[] icc = options.iccProfile();
        return icc == null ? null : new IccCmykTransform(icc);
    }

    /**
     * Runs the optional native image pass for one page, then the vector pass (the
     * image pass reloads and regenerates page content, so it must come first).
     */
    private static int convertDocumentPage(PdfDocument doc, int pageIndex,
                                           ColorConvertOptions options, IccCmykTransform xform) {
        int total = 0;
        if (options.convertImages()
                && options.targetColorSpace() == ColorSpace.CMYK
                && ColorBindings.imageConversionAvailable()) {
            int flags = options.strictImages() ? ColorBindings.IMAGE_STRICT : 0;
            if (!options.extractBlack()) flags |= ColorBindings.IMAGE_NO_GCR;
            int images = ColorBindings.pageImagesToCmyk(
                    doc.nativeHandle(), pageIndex, options.jpegQuality(), flags);
            if (images > 0) {
                total += images;
            } else if (options.strictImages() && images < 0) {
                throw new JPDFiumException("CMYK image conversion failed on page " + pageIndex);
            }
        }
        try (PdfPage page = doc.page(pageIndex)) {
            total += convertPage(page.rawHandle(), options, xform);
        }
        return total;
    }

    private static int convertPage(MemorySegment rawPage, ColorSpace target) {
        return convertPage(rawPage, ColorConvertOptions.builder()
                .targetColorSpace(target)
                .build(), null);
    }

    private static int convertPage(MemorySegment rawPage, ColorConvertOptions options) {
        return convertPage(rawPage, options, null);
    }

    private static int convertPage(MemorySegment rawPage, ColorConvertOptions options,
                                   IccCmykTransform xform) {
        ColorSpace target = options.targetColorSpace();
        if (!supportsColorSpace(target)) {
            throw new JPDFiumException("Unsupported color conversion target: " + target);
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
            int[] cmyk = new int[4];

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
                if (type == 3) continue;

                if (isText || isPath) {
                    if (convertColor(obj, options, xform, r, g, b, a, cmyk, true)) {
                        changed = true;
                        converted++;
                    }
                    if (convertColor(obj, options, xform, r, g, b, a, cmyk, false)) {
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            try {
                int ok = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
                if (ok == 0) {
                    throw new JPDFiumException("FPDFPage_GenerateContent failed");
                }
            } catch (JPDFiumException e) {
                throw e;
            } catch (Throwable t) {
                throw new JPDFiumException("FPDFPage_GenerateContent failed", t);
            }
        }
        return converted;
    }

    /** Reads, remaps and writes one fill or stroke colour using the shared slots. */
    private static boolean convertColor(MemorySegment obj, ColorConvertOptions options,
                                        IccCmykTransform xform,
                                        MemorySegment r, MemorySegment g, MemorySegment b, MemorySegment a,
                                        int[] cmyk, boolean fill) {
        try {
            int ok = fill
                    ? (int) PageEditBindings.FPDFPageObj_GetFillColor.invokeExact(obj, r, g, b, a)
                    : (int) PageEditBindings.FPDFPageObj_GetStrokeColor.invokeExact(obj, r, g, b, a);
            if (ok == 0) return false;

            int ri = r.get(ValueLayout.JAVA_INT, 0);
            int gi = g.get(ValueLayout.JAVA_INT, 0);
            int bi = b.get(ValueLayout.JAVA_INT, 0);
            int ai = a.get(ValueLayout.JAVA_INT, 0);
            // preserveBlack only guards the RGB paths; a CMYK target must still convert
            // pure black so it becomes K instead of staying an RGB colour.
            if (options.preserveBlack() && options.targetColorSpace() != ColorSpace.CMYK
                    && ri == 0 && gi == 0 && bi == 0) return false;

            ColorSpace target = options.targetColorSpace();
            if (target == ColorSpace.CMYK) {
                return convertColorToCmyk(obj, xform, options, ri, gi, bi, ai, cmyk, fill);
            }

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

    private static boolean convertColorToCmyk(MemorySegment obj, IccCmykTransform xform,
                                              ColorConvertOptions options,
                                              int r, int g, int b, int a, int[] cmyk, boolean fill) {
        if (xform != null) {
            xform.toCmyk(r, g, b, cmyk);
        } else {
            rgbToCmyk(r, g, b, options.extractBlack(), cmyk);
        }
        return fill
                ? ColorBindings.setFillColorCmyk(obj, cmyk[0], cmyk[1], cmyk[2], cmyk[3], a)
                : ColorBindings.setStrokeColorCmyk(obj, cmyk[0], cmyk[1], cmyk[2], cmyk[3], a);
    }

    /** RGB to grayscale using ITU-R BT.709 luminance coefficients. */
    private static int toGray(int r, int g, int b) {
        return Math.clamp(Math.round(0.2126f * r + 0.7152f * g + 0.0722f * b), 0, 255);
    }

    /** Naive GCR (profile-free) RGB to CMYK; {@code out} receives C/M/Y/K as 0-255. */
    static void rgbToCmyk(int r, int g, int b, boolean extractBlack, int[] out) {
        int c = 255 - r;
        int m = 255 - g;
        int y = 255 - b;
        int k = Math.min(c, Math.min(m, y));
        if (extractBlack) {
            c -= k;
            m -= k;
            y -= k;
        } else {
            k = 0;
        }
        out[0] = c;
        out[1] = m;
        out[2] = y;
        out[3] = k;
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
        private final byte[] iccProfile;
        private final boolean extractBlack;
        private final int jpegQuality;
        private final boolean strictImages;

        private ColorConvertOptions(Builder b) {
            this.targetColorSpace = b.targetColorSpace;
            this.convertImages = b.convertImages;
            this.convertVectors = b.convertVectors;
            this.convertText = b.convertText;
            this.preserveBlack = b.preserveBlack;
            this.iccProfile = b.iccProfile == null ? null : b.iccProfile.clone();
            this.extractBlack = b.extractBlack;
            this.jpegQuality = b.jpegQuality;
            this.strictImages = b.strictImages;
        }

        public ColorSpace targetColorSpace() { return targetColorSpace; }
        public boolean convertImages() { return convertImages; }
        public boolean convertVectors() { return convertVectors; }
        public boolean convertText() { return convertText; }
        public boolean preserveBlack() { return preserveBlack; }

        /** CMYK ICC profile for profile-aware vector conversion; images always use GCR. */
        public byte[] iccProfile() {
            return iccProfile == null ? null : iccProfile.clone();
        }

        /** Whether black generation subtracts K from C/M/Y (default true). */
        public boolean extractBlack() { return extractBlack; }

        /** JPEG quality used when re-encoding images as DeviceCMYK (default 90). */
        public int jpegQuality() { return jpegQuality; }

        /** Fail instead of skipping an image that cannot be converted (default false). */
        public boolean strictImages() { return strictImages; }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private ColorSpace targetColorSpace = ColorSpace.GRAYSCALE;
            private boolean convertImages = true;
            private boolean convertVectors = true;
            private boolean convertText = true;
            private boolean preserveBlack = true;
            private byte[] iccProfile;
            private boolean extractBlack = true;
            private int jpegQuality = 90;
            private boolean strictImages;

            private Builder() {}

            public Builder targetColorSpace(ColorSpace cs) { this.targetColorSpace = cs; return this; }
            public Builder convertImages(boolean v) { this.convertImages = v; return this; }
            public Builder convertVectors(boolean v) { this.convertVectors = v; return this; }
            public Builder convertText(boolean v) { this.convertText = v; return this; }
            public Builder preserveBlack(boolean v) { this.preserveBlack = v; return this; }

            /** Use a CMYK ICC profile for vector conversion; images always use GCR. */
            public Builder iccProfile(byte[] v) { this.iccProfile = v == null ? null : v.clone(); return this; }

            /** Toggle black generation for the naive (non-ICC) CMYK maths. */
            public Builder extractBlack(boolean v) { this.extractBlack = v; return this; }

            /** JPEG quality 1-100 for CMYK image re-encoding. */
            public Builder jpegQuality(int v) { this.jpegQuality = v; return this; }

            /** Fail the whole conversion when an image cannot be converted. */
            public Builder strictImages(boolean v) { this.strictImages = v; return this; }

            public ColorConvertOptions build() { return new ColorConvertOptions(this); }
        }
    }
}
