package stirling.software.jpdfium.doc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.ColorBindings;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PageEditBindings;

/**
 * Vector-level recolouring: inversion, tinting and high contrast.
 * Only top-level text and path objects change; form XObject content is left as-is.
 */
public final class PdfRecolor {

    /** White-ish colours are left alone by {@link Mode#INVERT} when enabled. */
    private static final int WHITE_FLOOR = 250;

    private PdfRecolor() {}

    /** Recolour strategy. */
    public enum Mode {
        /** {@code v' = 255 - v} per channel. */
        INVERT,
        /** Blend toward the configured tint colour by {@link RecolorOptions#strength()}. */
        TINT,
        /** Push values away from / toward the midpoint, then optionally threshold. */
        HIGH_CONTRAST
    }

    /** Invert every text/path colour (white fills preserved by default). */
    public static int invert(PdfDocument doc) {
        return apply(doc, RecolorOptions.builder().mode(Mode.INVERT).build());
    }

    /** Invert colours on specific pages. */
    public static int invert(PdfDocument doc, Set<Integer> pageIndices) {
        return apply(doc, RecolorOptions.builder().mode(Mode.INVERT).build(), pageIndices);
    }

    /** Tint every text/path colour toward the given RGB colour. */
    public static int tint(PdfDocument doc, int r, int g, int b, int strength) {
        return apply(doc, RecolorOptions.builder()
                .mode(Mode.TINT)
                .tintColor(r, g, b)
                .strength(strength)
                .build());
    }

    /** Increase contrast around mid-grey without thresholding. */
    public static int highContrast(PdfDocument doc, double factor) {
        return apply(doc, RecolorOptions.builder()
                .mode(Mode.HIGH_CONTRAST)
                .contrastFactor(factor)
                .build());
    }

    /** Binarise every text/path colour to pure black or white. */
    public static int binarize(PdfDocument doc, int threshold) {
        return apply(doc, RecolorOptions.builder()
                .mode(Mode.HIGH_CONTRAST)
                .threshold(threshold)
                .build());
    }

    /** Apply the given recolour options to every page. */
    public static int apply(PdfDocument doc, RecolorOptions options) {
        int total = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                total += recolorPage(page.rawHandle(), options);
            }
        }
        return total;
    }

    /** Apply the given recolour options to specific pages. */
    public static int apply(PdfDocument doc, RecolorOptions options, Set<Integer> pageIndices) {
        int total = 0;
        for (int i : pageIndices) {
            if (i >= 0 && i < doc.pageCount()) {
                try (PdfPage page = doc.page(i)) {
                    total += recolorPage(page.rawHandle(), options);
                }
            }
        }
        return total;
    }

    private static int recolorPage(MemorySegment rawPage, RecolorOptions options) {
        int count;
        try {
            count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return 0;
        }
        if (count <= 0) return 0;

        int changed = 0;
        boolean dirty = false;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment r = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment g = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment a = arena.allocate(ValueLayout.JAVA_INT);
            int[] mapped = new int[3];
            int[] cmykProbe = new int[5];

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
                // 1 = TEXT, 2 = PATH. Images and form XObjects (5) are skipped.
                if (type != 1 && type != 2) continue;

                boolean objChanged = recolorOne(obj, options, r, g, b, a, mapped, cmykProbe, true);
                if (recolorOne(obj, options, r, g, b, a, mapped, cmykProbe, false)) {
                    objChanged = true;
                }
                if (objChanged) {
                    dirty = true;
                    changed++;
                }
            }
        }

        if (dirty) {
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
        return changed;
    }

    private static boolean recolorOne(MemorySegment obj, RecolorOptions options,
                                      MemorySegment r, MemorySegment g, MemorySegment b, MemorySegment a,
                                      int[] mapped, int[] cmykProbe, boolean fill) {
        try {
            // The CMYK getters answer true only for DeviceCMYK, so this skips space
            // colours we cannot faithfully rewrite (ICC/Separation read back as sRGB).
            if (fill ? ColorBindings.getFillColorCmyk(obj, cmykProbe)
                     : ColorBindings.getStrokeColorCmyk(obj, cmykProbe)) {
                return false;
            }
            int ok = fill
                    ? (int) PageEditBindings.FPDFPageObj_GetFillColor.invokeExact(obj, r, g, b, a)
                    : (int) PageEditBindings.FPDFPageObj_GetStrokeColor.invokeExact(obj, r, g, b, a);
            if (ok == 0) return false;

            int ri = r.get(ValueLayout.JAVA_INT, 0);
            int gi = g.get(ValueLayout.JAVA_INT, 0);
            int bi = b.get(ValueLayout.JAVA_INT, 0);
            int ai = a.get(ValueLayout.JAVA_INT, 0);

            if (!options.map(ri, gi, bi, mapped)) return false;

            int setOk = fill
                    ? (int) PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(
                            obj, mapped[0], mapped[1], mapped[2], ai)
                    : (int) PageEditBindings.FPDFPageObj_SetStrokeColor.invokeExact(
                            obj, mapped[0], mapped[1], mapped[2], ai);
            return setOk != 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    /** Recolour options (builder pattern). */
    public static final class RecolorOptions {
        private final Mode mode;
        private final int tintR;
        private final int tintG;
        private final int tintB;
        private final int strength;
        private final double contrastFactor;
        private final int threshold;
        private final boolean preserveWhite;

        private RecolorOptions(Builder b) {
            this.mode = b.mode;
            this.tintR = b.tintR;
            this.tintG = b.tintG;
            this.tintB = b.tintB;
            this.strength = b.strength;
            this.contrastFactor = b.contrastFactor;
            this.threshold = b.threshold;
            this.preserveWhite = b.preserveWhite;
        }

        public Mode mode() { return mode; }
        public int tintColorR() { return tintR; }
        public int tintColorG() { return tintG; }
        public int tintColorB() { return tintB; }
        public int strength() { return strength; }
        public double contrastFactor() { return contrastFactor; }
        public int threshold() { return threshold; }
        public boolean preserveWhite() { return preserveWhite; }

        /** Fill {@code out} with the remapped RGB; false when the colour stays as it is. */
        boolean map(int r, int g, int b, int[] out) {
            switch (mode) {
                case INVERT:
                    return invert(r, g, b, out);
                case TINT:
                    return tint(r, g, b, out);
                case HIGH_CONTRAST:
                default:
                    return contrast(r, g, b, out);
            }
        }

        private boolean invert(int r, int g, int b, int[] out) {
            if (preserveWhite && r >= WHITE_FLOOR && g >= WHITE_FLOOR && b >= WHITE_FLOOR) {
                return false;
            }
            out[0] = 255 - r;
            out[1] = 255 - g;
            out[2] = 255 - b;
            return true;
        }

        private boolean tint(int r, int g, int b, int[] out) {
            if (strength <= 0) return false;
            int t = Math.clamp(strength, 0, 100);
            if (t == 100) {
                out[0] = tintR;
                out[1] = tintG;
                out[2] = tintB;
                return true;
            }
            int inv = 100 - t;
            out[0] = (r * inv + tintR * t) / 100;
            out[1] = (g * inv + tintG * t) / 100;
            out[2] = (b * inv + tintB * t) / 100;
            return true;
        }

        private boolean contrast(int r, int g, int b, int[] out) {
            if (threshold >= 0) {
                int luma = Math.clamp(Math.round(0.2126f * r + 0.7152f * g + 0.0722f * b), 0, 255);
                int v = luma <= threshold ? 0 : 255;
                if (r == v && g == v && b == v) return false;
                out[0] = v;
                out[1] = v;
                out[2] = v;
                return true;
            }
            double f = contrastFactor;
            if (f == 1.0d) return false;
            out[0] = stretch(r, f);
            out[1] = stretch(g, f);
            out[2] = stretch(b, f);
            return true;
        }

        private static int stretch(int v, double factor) {
            double centred = 128.0d + (v - 128.0d) * factor;
            return Math.clamp((int) Math.round(centred), 0, 255);
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private Mode mode = Mode.INVERT;
            private int tintR = 255;
            private int tintG = 255;
            private int tintB = 255;
            private int strength = 50;
            private double contrastFactor = 1.5d;
            private int threshold = -1;
            private boolean preserveWhite = true;

            private Builder() {}

            public Builder mode(Mode m) { this.mode = m; return this; }

            /** Tint target colour, components 0-255. */
            public Builder tintColor(int r, int g, int b) {
                this.tintR = Math.clamp(r, 0, 255);
                this.tintG = Math.clamp(g, 0, 255);
                this.tintB = Math.clamp(b, 0, 255);
                return this;
            }

            /** Tint blend strength 0-100. */
            public Builder strength(int s) { this.strength = Math.clamp(s, 0, 100); return this; }

            /** Contrast multiplier for {@link Mode#HIGH_CONTRAST} (1.0 = no-op). */
            public Builder contrastFactor(double f) { this.contrastFactor = f; return this; }

            /** Luminance threshold 0-255; setting it enables black/white binarisation. */
            public Builder threshold(int t) { this.threshold = Math.clamp(t, 0, 255); return this; }

            /** Keep near-white fills unchanged during inversion (default true). */
            public Builder preserveWhite(boolean v) { this.preserveWhite = v; return this; }

            public RecolorOptions build() { return new RecolorOptions(this); }
        }
    }
}
