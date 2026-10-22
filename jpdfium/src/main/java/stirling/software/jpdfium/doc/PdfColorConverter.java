package stirling.software.jpdfium.doc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.ColorBindings;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PageEditBindings;

/**
 * Converts page-object fill/stroke colours to grayscale, RGB or DeviceCMYK.
 * CMYK and form write-back require the JPDFium PDFium patch. A CMYK target
 * preserves existing DeviceCMYK, DeviceGray and special colours.
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
        // Colour-space probes prevent flattening CMYK/ICC/spot through sRGB.
        return target == ColorSpace.CMYK
                && ColorBindings.vectorCmykAvailable()
                && ColorBindings.colorSpaceAvailable()
                && ColorBindings.iccConversionAvailable();
    }

    /** Color spaces this converter can emit. */
    public static Set<ColorSpace> supportedColorSpaces() {
        EnumSet<ColorSpace> spaces = EnumSet.of(ColorSpace.GRAYSCALE, ColorSpace.RGB);
        if (supportsColorSpace(ColorSpace.CMYK)) spaces.add(ColorSpace.CMYK);
        return spaces;
    }

    /** True if image XObjects can be re-encoded as DeviceCMYK JPEGs in this build. */
    public static boolean supportsImageConversion() {
        return ColorBindings.imageConversionAvailable();
    }

    /** True when form XObject content can be traversed and written back. */
    public static boolean supportsFormConversion() {
        return PageEditBindings.formConversionAvailable();
    }

    /**
     * Convert all pages to grayscale.
     *
     * @param doc the document to modify (in place)
     * @return number of objects whose colors were converted
     */
    public static int toGrayscale(PdfDocument doc) {
        Set<Long> formIds = new HashSet<>();
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                total += convertPage(page.rawHandle(), ColorSpace.GRAYSCALE, formIds);
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
        Set<Long> formIds = new HashSet<>();
        int total = 0;
        for (int i : pageIndices) {
            if (i >= 0 && i < doc.pageCount()) {
                try (PdfPage page = doc.page(i)) {
                    total += convertPage(page.rawHandle(), ColorSpace.GRAYSCALE, formIds);
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
        Set<Long> formIds = new HashSet<>();
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                total += convertPage(page.rawHandle(),
                        ColorConvertOptions.builder()
                                .targetColorSpace(ColorSpace.RGB)
                                .preserveBlack(false)
                                .build(), formIds);
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
        Set<Long> formIds = new HashSet<>();
        int total = 0;
        for (int i : pageIndices) {
            if (i >= 0 && i < doc.pageCount()) {
                try (PdfPage page = doc.page(i)) {
                    total += convertPage(page.rawHandle(), ColorConvertOptions.builder()
                            .targetColorSpace(ColorSpace.RGB)
                            .preserveBlack(false)
                            .build(), formIds);
                }
            }
        }
        return total;
    }

    /** Convert DeviceRGB page colours to DeviceCMYK (throws when the CMYK setters are absent). */
    public static int toCmyk(PdfDocument doc) {
        return convert(doc, ColorConvertOptions.builder()
                .targetColorSpace(ColorSpace.CMYK)
                .build());
    }

    /** Convert DeviceRGB colours on specific pages to DeviceCMYK (throws when the setters are absent). */
    public static int toCmyk(PdfDocument doc, Set<Integer> pageIndices) {
        ColorConvertOptions options = ColorConvertOptions.builder()
                .targetColorSpace(ColorSpace.CMYK)
                .build();
        requireCmykSupport(options);
        IccCmykTransform xform = cmykTransform(options);
        Set<Long> formIds = new HashSet<>();
        int total = 0;
        for (int i : pageIndices) {
            if (i >= 0 && i < doc.pageCount()) {
                total += convertDocumentPage(doc, i, options, xform, formIds);
            }
        }
        return total;
    }

    /**
     * Re-encode every convertible image XObject on every page as a DeviceCMYK
     * JPEG. Only DCT (JPEG) sources are converted by default; Flate and other
     * lossless sources keep their original bytes so line art and screenshots
     * are not damaged. Returns the count, or {@code -1} when the encoder is
     * unavailable or a page failed.
     */
    public static int convertImagesToCmyk(PdfDocument doc, int quality) {
        return convertImagesToCmyk(doc, quality, false);
    }

    /**
     * As {@link #convertImagesToCmyk(PdfDocument, int)}, with an explicit opt-in
     * for converting lossless sources to JPEG.
     */
    public static int convertImagesToCmyk(PdfDocument doc, int quality, boolean convertLossless) {
        if (!ColorBindings.imageConversionAvailable()) return -1;
        int flags = convertLossless ? ColorBindings.IMAGE_CONVERT_LOSSLESS : 0;
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            int n = ColorBindings.pageImagesToCmyk(doc.nativeHandle(), i, quality, flags);
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
        Set<Long> formIds = new HashSet<>();
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            total += convertDocumentPage(doc, i, options, xform, formIds);
        }
        return total;
    }

    /** Reject CMYK requests the loaded library cannot satisfy, with an actionable message. */
    private static void requireCmykSupport(ColorConvertOptions options) {
        if (options.targetColorSpace() == ColorSpace.CMYK
                && (!ColorBindings.vectorCmykAvailable()
                        || !ColorBindings.colorSpaceAvailable()
                        || !ColorBindings.iccConversionAvailable())) {
            throw new JPDFiumException("Unsupported color conversion target: CMYK"
                    + " (this PDFium build lacks the CMYK page-object accessors or"
                    + " the colour-space probe; PDFium must be rebuilt with the"
                    + " JPDFium colour patch and the prebuilt pin bumped. Image-only"
                    + " CMYK conversion is available now via"
                    + " PdfColorConverter.convertImagesToCmyk)");
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
                                           ColorConvertOptions options, IccCmykTransform xform,
                                           Set<Long> formIds) {
        int total = 0;
        if (options.convertImages()
                && options.targetColorSpace() == ColorSpace.CMYK
                && ColorBindings.imageConversionAvailable()) {
            int flags = options.strictImages() ? ColorBindings.IMAGE_STRICT : 0;
            if (!options.extractBlack()) flags |= ColorBindings.IMAGE_NO_GCR;
            if (options.convertLosslessImages()) flags |= ColorBindings.IMAGE_CONVERT_LOSSLESS;
            int images;
            if (xform != null) {
                images = ColorBindings.pageImagesToCmyk(
                        doc.nativeHandle(), pageIndex, options.jpegQuality(), flags,
                        xform.lutBytes(), IccCmykTransform.lutGrid());
            } else {
                images = ColorBindings.pageImagesToCmyk(
                        doc.nativeHandle(), pageIndex, options.jpegQuality(), flags);
            }
            if (images > 0) {
                total += images;
            } else if (options.strictImages() && images < 0) {
                throw new JPDFiumException("CMYK image conversion failed on page " + pageIndex);
            }
        }
        try (PdfPage page = doc.page(pageIndex)) {
            total += convertPage(page.rawHandle(), options, xform, formIds);
        }
        return total;
    }

    private static int convertPage(MemorySegment rawPage, ColorSpace target, Set<Long> formIds) {
        return convertPage(rawPage, ColorConvertOptions.builder()
                .targetColorSpace(target)
                .build(), null, formIds);
    }

    private static int convertPage(MemorySegment rawPage, ColorConvertOptions options,
                                   Set<Long> formIds) {
        return convertPage(rawPage, options, null, formIds);
    }

    private static int convertPage(MemorySegment rawPage, ColorConvertOptions options,
                                   IccCmykTransform xform, Set<Long> formIds) {
        ColorSpace target = options.targetColorSpace();
        if (!supportsColorSpace(target)) {
            throw new JPDFiumException("Unsupported color conversion target: " + target);
        }

        ConversionState state = new ConversionState(formIds);
        // One arena and four int slots are reused for every object on the page.
        // Allocating them per fill/stroke call dominated the allocation profile.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment r = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment g = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment a = arena.allocate(ValueLayout.JAVA_INT);
            int[] cmyk = new int[4];
            scanPageObjects(rawPage, options, xform, r, g, b, a, cmyk, state);
        }

        if (state.dirty) {
            generateContent(rawPage);
        }
        return state.converted;
    }

    private static final class ConversionState {
        // Shared across pages so a shared form is converted once.
        private final Set<Long> visitedForms;
        private int converted;
        private int edits;
        private boolean dirty;

        private ConversionState(Set<Long> visitedForms) {
            this.visitedForms = visitedForms;
        }
    }

    private static void scanPageObjects(MemorySegment rawPage, ColorConvertOptions options,
                                        IccCmykTransform xform,
                                        MemorySegment r, MemorySegment g, MemorySegment b, MemorySegment a,
                                        int[] cmyk, ConversionState state) {
        int count;
        try {
            count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return;
        }
        for (int i = 0; i < count; i++) {
            MemorySegment obj;
            try {
                obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                continue;
            }
            scanObject(obj, options, xform, r, g, b, a, cmyk, state);
        }
    }

    // Recurses into forms when dirty-stream write-back is available.
    private static void scanObject(MemorySegment obj, ColorConvertOptions options,
                                   IccCmykTransform xform,
                                   MemorySegment r, MemorySegment g, MemorySegment b, MemorySegment a,
                                   int[] cmyk, ConversionState state) {
        if (obj == null || obj.equals(MemorySegment.NULL)) return;

        int type;
        try {
            type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return;
        }

        if (type == 5) {
            if (!options.convertForms() || !PageEditBindings.formConversionAvailable()) return;
            if (!state.visitedForms.add(PageEditBindings.formIdentity(obj))) return;

            int beforeEdits = state.edits;
            scanFormObjects(obj, options, xform, r, g, b, a, cmyk, state);
            if (state.edits != beforeEdits) {
                int ok;
                try {
                    ok = (int) PageEditBindings.FPDFFormObj_MarkDirty.invokeExact(obj);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    throw new JPDFiumException("FPDFFormObj_MarkDirty failed", t);
                }
                if (ok == 0) {
                    throw new JPDFiumException("FPDFFormObj_MarkDirty failed");
                }
            }
            return;
        }

        // Type 1 = TEXT, 2 = PATH, 3 = IMAGE, 4 = SHADING, 5 = FORM.
        boolean isText = (type == 1);
        boolean isPath = (type == 2);
        if (!isText && !isPath) return;
        if (isText && !options.convertText()) return;
        if (isPath && !options.convertVectors()) return;

        if (convertColor(obj, options, xform, r, g, b, a, cmyk, true)) {
            state.dirty = true;
            state.edits++;
            state.converted++;
        }
        if (convertColor(obj, options, xform, r, g, b, a, cmyk, false)) {
            state.dirty = true;
            state.edits++;
        }
    }

    private static void scanFormObjects(MemorySegment form, ColorConvertOptions options,
                                        IccCmykTransform xform,
                                        MemorySegment r, MemorySegment g, MemorySegment b, MemorySegment a,
                                        int[] cmyk, ConversionState state) {
        int count;
        try {
            count = (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(form);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return;
        }
        for (int i = 0; i < count; i++) {
            MemorySegment child;
            try {
                child = (MemorySegment) PageEditBindings.FPDFFormObj_GetObject.invokeExact(
                        form, (long) i);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                continue;
            }
            scanObject(child, options, xform, r, g, b, a, cmyk, state);
        }
    }

    private static void generateContent(MemorySegment rawPage) {
        int ok;
        try {
            ok = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("FPDFPage_GenerateContent failed", t);
        }
        if (ok == 0) {
            throw new JPDFiumException("FPDFPage_GenerateContent failed");
        }
    }

    /** Reads, remaps and writes one fill or stroke colour using the shared slots. */
    private static boolean convertColor(MemorySegment obj, ColorConvertOptions options,
                                        IccCmykTransform xform,
                                        MemorySegment r, MemorySegment g, MemorySegment b, MemorySegment a,
                                        int[] cmyk, boolean fill) {
        try {
            ColorSpace target = options.targetColorSpace();
            int sourceSpace = -1;
            if (target == ColorSpace.CMYK) {
                sourceSpace = fill
                        ? ColorBindings.fillColorSpace(obj)
                        : ColorBindings.strokeColorSpace(obj);
                // Preserve raw ink values; the RGB getter would alter 100% K.
                if (sourceSpace == ColorBindings.COLORSPACE_DEVICECMYK) return false;
                // DeviceGray is a valid PDF/X process colour and already means K.
                // Leaving it alone also avoids regenerating pages that only have
                // gray strokes, which can shift text and spot-colour artwork.
                if (sourceSpace == ColorBindings.COLORSPACE_DEVICEGRAY) return false;
                // Never flatten patterns.
                if (sourceSpace == ColorBindings.COLORSPACE_PATTERN) return false;

                if (sourceSpace == ColorBindings.COLORSPACE_ICCBASED) {
                    if (!options.convertSpecialColors()) return false;
                    if (convertIccToCmyk(obj, xform, options, r, g, b, a, cmyk, fill)) return true;
                } else if (sourceSpace == ColorBindings.COLORSPACE_SEPARATION
                        || sourceSpace == ColorBindings.COLORSPACE_DEVICEN) {
                    if (!options.convertSpotColors()) return false;
                } else if (!ColorBindings.isProcessColorSpace(sourceSpace)
                        && !options.convertSpecialColors()) {
                    return false;
                }
            }

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
        return setCmykColor(obj, cmyk, a, fill);
    }

    // Returns false when the source profile/components are unavailable.
    private static boolean convertIccToCmyk(MemorySegment obj, IccCmykTransform xform,
                                            ColorConvertOptions options,
                                            MemorySegment r, MemorySegment g, MemorySegment b,
                                            MemorySegment a, int[] cmyk, boolean fill) {
        float[] comps = fill
                ? ColorBindings.fillColorComponents(obj)
                : ColorBindings.strokeColorComponents(obj);
        byte[] sourceIcc = fill
                ? ColorBindings.fillColorIccProfile(obj)
                : ColorBindings.strokeColorIccProfile(obj);
        if (comps == null || sourceIcc == null) return false;
        try {
            if (xform != null) {
                xform.toCmykFromIcc(sourceIcc, comps, cmyk);
            } else {
                IccCmykTransform.sourceToCmyk(sourceIcc, comps, options.extractBlack(), cmyk);
            }
        } catch (JPDFiumException e) {
            return false;
        }
        int alpha;
        try {
            int ok = fill
                    ? (int) PageEditBindings.FPDFPageObj_GetFillColor.invokeExact(obj, r, g, b, a)
                    : (int) PageEditBindings.FPDFPageObj_GetStrokeColor.invokeExact(obj, r, g, b, a);
            if (ok == 0) return false;
            alpha = a.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
        return setCmykColor(obj, cmyk, alpha, fill);
    }

    private static boolean setCmykColor(MemorySegment obj, int[] cmyk, int a, boolean fill) {
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
        private final boolean convertForms;
        private final boolean convertSpecialColors;
        private final boolean convertSpotColors;
        private final boolean preserveBlack;
        private final byte[] iccProfile;
        private final boolean extractBlack;
        private final int jpegQuality;
        private final boolean strictImages;
        private final boolean convertLosslessImages;

        private ColorConvertOptions(Builder b) {
            this.targetColorSpace = b.targetColorSpace;
            this.convertImages = b.convertImages;
            this.convertVectors = b.convertVectors;
            this.convertText = b.convertText;
            this.convertForms = b.convertForms;
            this.convertSpecialColors = b.convertSpecialColors;
            this.convertSpotColors = b.convertSpotColors;
            this.preserveBlack = b.preserveBlack;
            this.iccProfile = b.iccProfile == null ? null : b.iccProfile.clone();
            this.extractBlack = b.extractBlack;
            this.jpegQuality = b.jpegQuality;
            this.strictImages = b.strictImages;
            this.convertLosslessImages = b.convertLosslessImages;
        }

        public ColorSpace targetColorSpace() { return targetColorSpace; }
        public boolean convertImages() { return convertImages; }
        public boolean convertVectors() { return convertVectors; }
        public boolean convertText() { return convertText; }
        public boolean convertForms() { return convertForms; }
        public boolean convertSpecialColors() { return convertSpecialColors; }
        public boolean convertSpotColors() { return convertSpotColors; }
        public boolean preserveBlack() { return preserveBlack; }

        /** CMYK ICC profile; vector and image pixels share its lookup table. */
        public byte[] iccProfile() {
            return iccProfile == null ? null : iccProfile.clone();
        }

        /** Whether black generation subtracts K from C/M/Y (default true). */
        public boolean extractBlack() { return extractBlack; }

        /** JPEG quality used when re-encoding images as DeviceCMYK (default 90). */
        public int jpegQuality() { return jpegQuality; }

        /** Fail instead of skipping an image that cannot be converted (default false). */
        public boolean strictImages() { return strictImages; }

        /**
         * Also convert Flate/other lossless images to JPEG. Off by default:
         * those sources are left untouched to avoid lossy re-encoding.
         */
        public boolean convertLosslessImages() { return convertLosslessImages; }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private ColorSpace targetColorSpace = ColorSpace.GRAYSCALE;
            private boolean convertImages = true;
            private boolean convertVectors = true;
            private boolean convertText = true;
            private boolean convertForms = true;
            private boolean convertSpecialColors;
            private boolean convertSpotColors;
            private boolean preserveBlack = true;
            private byte[] iccProfile;
            private boolean extractBlack = true;
            private int jpegQuality = 90;
            private boolean strictImages;
            private boolean convertLosslessImages;

            private Builder() {}

            public Builder targetColorSpace(ColorSpace cs) { this.targetColorSpace = cs; return this; }
            public Builder convertImages(boolean v) { this.convertImages = v; return this; }
            public Builder convertVectors(boolean v) { this.convertVectors = v; return this; }
            public Builder convertText(boolean v) { this.convertText = v; return this; }
            /** Recurse into form XObjects when the patched PDFium is available. */
            public Builder convertForms(boolean v) { this.convertForms = v; return this; }

            /**
             * Convert ICCBased/Cal/Lab/Indexed colours. ICCBased objects use
             * their source profile when one is available. Spots stay preserved
             * unless {@link #convertSpotColors(boolean)} is also enabled.
             */
            public Builder convertSpecialColors(boolean v) { this.convertSpecialColors = v; return this; }

            /** Convert Separation/DeviceN spots to process CMYK (opt-in; names/overprint are lost). */
            public Builder convertSpotColors(boolean v) { this.convertSpotColors = v; return this; }

            public Builder preserveBlack(boolean v) { this.preserveBlack = v; return this; }

            /** Use a CMYK ICC profile for vector and image conversion through one shared LUT. */
            public Builder iccProfile(byte[] v) { this.iccProfile = v == null ? null : v.clone(); return this; }

            /** Toggle black generation for the naive (non-ICC) CMYK maths. */
            public Builder extractBlack(boolean v) { this.extractBlack = v; return this; }

            /** JPEG quality 1-100 for CMYK image re-encoding. */
            public Builder jpegQuality(int v) { this.jpegQuality = v; return this; }

            /** Fail the whole conversion when an image cannot be converted. */
            public Builder strictImages(boolean v) { this.strictImages = v; return this; }

            /** Also convert lossless (Flate) images to JPEG; off by default. */
            public Builder convertLosslessImages(boolean v) {
                this.convertLosslessImages = v;
                return this;
            }

            public ColorConvertOptions build() { return new ColorConvertOptions(this); }
        }
    }
}
