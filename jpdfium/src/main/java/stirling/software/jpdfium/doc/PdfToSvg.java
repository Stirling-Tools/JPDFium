package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfImageIO;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.ShapedGlyph;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PathBindings;
import stirling.software.jpdfium.panama.TextPageBindings;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.TextChar;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Export a PDF page to SVG.
 * <p>The exporter walks the page object list through the PDFium object API and re-creates what it
 * can as native SVG elements, covering the rest with clipped raster patches.
 */
public final class PdfToSvg {

    /** Upper bound on a single multi-page SVG request. */
    private static final int MAX_EXPORT_PAGES = 10_000;

    private PdfToSvg() {}

    /** Tuning for {@link #toSvg(byte[], int, Options)}. */
    public static final class Options {

        /** Default options: text, images and form traversal on, raster fallback on at 144 dpi. */
        public Options() {}

        private boolean includeText = true;
        private boolean includeImages = true;
        private boolean includeForms = true;
        private boolean rasterFallback = true;
        private boolean outlineText = false;
        private int fallbackDpi = 144;

        public Options includeText(boolean value) {
            this.includeText = value;
            return this;
        }

        public Options includeImages(boolean value) {
            this.includeImages = value;
            return this;
        }

        public Options includeForms(boolean value) {
            this.includeForms = value;
            return this;
        }

        /**
         * When true (the default) constructs this API cannot express in SVG — shading, and form
         * children that are not paths — are covered with a clipped raster render of the page. When
         * false those areas are left blank.
         */
        public Options rasterFallback(boolean value) {
            this.rasterFallback = value;
            return this;
        }

        /** Resolution of the clipped raster patches used by the fallback. */
        public Options fallbackDpi(int dpi) {
            this.fallbackDpi = dpi <= 0 ? 144 : dpi;
            return this;
        }

        /**
         * When true, text is converted to {@code <path>} glyph outlines using the embedded font
         * program instead of {@code <text>} elements. The output no longer depends on the viewer
         * having the font, at the cost of a larger document.
         */
        public Options outlineText(boolean value) {
            this.outlineText = value;
            return this;
        }
    }

    /** Export one page with default options. */
    public static String toSvg(byte[] pdf, int pageIndex) {
        return toSvg(pdf, pageIndex, new Options());
    }

    /**
     * Export one page to an SVG document.
     * @param pdf PDF bytes
     */
    public static String toSvg(byte[] pdf, int pageIndex, Options options) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf must not be empty");
        }
        Options opts = options != null ? options : new Options();
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            if (pageIndex < 0 || pageIndex >= doc.pageCount()) {
                throw new IllegalArgumentException(
                        "page index out of range: " + pageIndex + " (pages: " + doc.pageCount() + ")");
            }
            return pageToSvg(doc, Shadings.ofBytes(pdf), pageIndex, opts);
        }
    }

    /**
     * Export one page from a PDF file, reading the shadings off disk instead of a heap copy.
     * @param path PDF file
     */
    public static String toSvg(Path path, int pageIndex) {
        return toSvg(path, pageIndex, new Options());
    }

    /** Export one page from a PDF file with explicit options. */
    public static String toSvg(Path path, int pageIndex, Options options) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        Options opts = options != null ? options : new Options();
        try (PdfDocument doc = PdfDocument.open(path)) {
            if (pageIndex < 0 || pageIndex >= doc.pageCount()) {
                throw new IllegalArgumentException(
                        "page index out of range: " + pageIndex + " (pages: " + doc.pageCount() + ")");
            }
            return pageToSvg(doc, Shadings.ofFile(path, new int[] {pageIndex}), pageIndex, opts);
        }
    }

    /** Export several pages of a PDF file as one SVG document with default options. */
    public static String toSvgDocument(Path path, int... pageIndices) {
        return toSvgDocument(path, new Options(), pageIndices);
    }

    /**
     * Export several pages of a PDF file as one SVG document, parsing the document once for
     * every requested page's shadings.
     */
    public static String toSvgDocument(Path path, Options options, int... pageIndices) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (pageIndices == null || pageIndices.length == 0) {
            throw new IllegalArgumentException("at least one page index is required");
        }
        if (pageIndices.length > MAX_EXPORT_PAGES) {
            throw new IllegalArgumentException("too many pages requested: " + pageIndices.length);
        }
        Options opts = options != null ? options : new Options();
        try (PdfDocument doc = PdfDocument.open(path)) {
            return document(doc, Shadings.ofFile(path, pageIndices), opts, pageIndices);
        }
    }

    /** Export one page of a PDF file and return the SVG as UTF-8 bytes. */
    public static byte[] toSvgBytes(Path path, int pageIndex) {
        return toSvg(path, pageIndex).getBytes(StandardCharsets.UTF_8);
    }

    /** Export one page of a PDF file with options and return the SVG as UTF-8 bytes. */
    public static byte[] toSvgBytes(Path path, int pageIndex, Options options) {
        return toSvg(path, pageIndex, options).getBytes(StandardCharsets.UTF_8);
    }

    /** Export several pages as one SVG document: a root {@code <svg>} holding one nested viewport per page, stacked vertically. */
    public static String toSvgDocument(byte[] pdf, int... pageIndices) {
        return toSvgDocument(pdf, new Options(), pageIndices);
    }

    /** Export several pages as one SVG document with explicit options. */
    public static String toSvgDocument(byte[] pdf, Options options, int... pageIndices) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf must not be empty");
        }
        if (pageIndices == null || pageIndices.length == 0) {
            throw new IllegalArgumentException("at least one page index is required");
        }
        if (pageIndices.length > MAX_EXPORT_PAGES) {
            throw new IllegalArgumentException("too many pages requested: " + pageIndices.length);
        }
        Options opts = options != null ? options : new Options();
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return document(doc, Shadings.ofBytes(pdf, pageIndices), opts, pageIndices);
        }
    }

    private static String document(PdfDocument doc, Shadings shadings, Options opts,
            int[] pageIndices) {
        float gap = 8f;
        float totalWidth = 0f;
        float totalHeight = 0f;
        float[] widths = new float[pageIndices.length];
        float[] heights = new float[pageIndices.length];
        for (int i = 0; i < pageIndices.length; i++) {
            int idx = pageIndices[i];
            if (idx < 0 || idx >= doc.pageCount()) {
                throw new IllegalArgumentException("page index out of range: " + idx);
            }
            try (PdfPage page = doc.page(idx)) {
                widths[i] = page.size().width();
                heights[i] = page.size().height();
            }
            totalWidth = Math.max(totalWidth, widths[i]);
            totalHeight += heights[i] + (i > 0 ? gap : 0f);
        }
        StringBuilder out = new StringBuilder(4096);
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
                .append("width=\"").append(num(totalWidth)).append("\" ")
                .append("height=\"").append(num(totalHeight)).append("\" ")
                .append("viewBox=\"0 0 ").append(num(totalWidth)).append(' ')
                .append(num(totalHeight)).append("\">\n");
        float offset = 0f;
        for (int i = 0; i < pageIndices.length; i++) {
            out.append(pageViewport(doc, shadings, pageIndices[i], opts, offset, widths[i], heights[i]));
            offset += heights[i] + (i + 1 < pageIndices.length ? gap : 0f);
        }
        out.append("</svg>\n");
        return out.toString();
    }

    /** Export one page and return the SVG as UTF-8 bytes. */
    public static byte[] toSvgBytes(byte[] pdf, int pageIndex) {
        return toSvg(pdf, pageIndex).getBytes(StandardCharsets.UTF_8);
    }

    /** Export one page with options and return the SVG as UTF-8 bytes. */
    public static byte[] toSvgBytes(byte[] pdf, int pageIndex, Options options) {
        return toSvg(pdf, pageIndex, options).getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- pages

    private static String pageToSvg(PdfDocument doc, Shadings shadings, int pageIndex, Options options) {
        StringBuilder out = new StringBuilder(4096);
        try (PdfPage page = doc.page(pageIndex)) {
            float w = page.size().width();
            float h = page.size().height();
            out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                    .append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
                    .append("width=\"").append(num(w)).append("pt\" ")
                    .append("height=\"").append(num(h)).append("pt\" ")
                    .append("viewBox=\"0 0 ").append(num(w)).append(' ').append(num(h)).append("\">\n");
            body(doc, shadings, page, pageIndex, options, out);
            out.append("</svg>\n");
        }
        return out.toString();
    }

    private static String pageViewport(PdfDocument doc, Shadings shadings, int pageIndex, Options options,
            float offsetY, float width, float height) {
        StringBuilder body = new StringBuilder(4096);
        try (PdfPage page = doc.page(pageIndex)) {
            body(doc, shadings, page, pageIndex, options, body);
        }
        return "<svg x=\"0\" y=\"" + num(offsetY) + "\" width=\"" + num(width) + "\" height=\""
                + num(height) + "\" viewBox=\"0 0 " + num(width) + ' ' + num(height) + "\">\n" + body
                + "</svg>\n";
    }

    private static void body(PdfDocument doc, Shadings shadings, PdfPage page, int pageIndex,
            Options options, StringBuilder out) {
        float w = page.size().width();
        float h = page.size().height();
        MemorySegment rawPage = page.rawHandleForRead();
        List<PageObject> objects;
        List<ExtractedImage> images;
        try {
            objects = PdfPageObjects.list(rawPage);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            objects = List.of();
        }
        try {
            images = options.includeImages
                    ? PdfImageExtractor.extract(doc.rawHandleForRead(), rawPage, pageIndex)
                    : List.of();
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            images = List.of();
        }

        Patch patch = new Patch(page, options, pageIndex, shadings.forPage(pageIndex), w, h);

        // PDF page space is bottom-left origin; SVG is top-left. One flip at the root keeps every
        // emitted coordinate in PDF numbers.
        StringBuilder group = new StringBuilder(4096);
        group.append("<g transform=\"translate(0 ").append(num(h)).append(") scale(1 -1)\">\n");
        for (PageObject obj : objects) {
            try {
                switch (obj.type()) {
                    case PATH -> emitPathClipped(group, obj, patch);
                    case IMAGE -> {
                        if (options.includeImages) {
                            StringBuilder content = new StringBuilder(256);
                            emitImage(content, obj, images);
                            emitClipped(group, obj, patch, content);
                        }
                    }
                    case FORM -> {
                        if (options.includeForms) {
                            StringBuilder content = new StringBuilder(512);
                            emitForm(content, obj.rawHandle(), 0, patch);
                            emitClipped(group, obj, patch, content);
                        }
                    }
                    case SHADING -> {
                        if (options.rasterFallback) {
                            emitRasterPatch(group, patch, obj);
                        }
                    }
                    default -> {
                        // TEXT is emitted as runs below; UNKNOWN has nothing to emit.
                    }
                }
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                // One malformed object must not abort the page: fall back to a raster patch when
                // enabled so the content is still visible.
                if (options.rasterFallback) {
                    try {
                        emitRasterPatch(group, patch, obj);
                    } catch (Throwable ignored) {
                        NativeRuntime.rethrowFatal(ignored);
                        // nothing else we can do for this object
                    }
                }
            }
        }
        if (options.includeText) {
            try {
                if (options.outlineText) {
                    emitTextOutlines(group, doc, pageIndex, rawPage, objects);
                } else {
                    emitText(group, doc, pageIndex, objects);
                }
            } catch (Throwable ignored) {
                NativeRuntime.rethrowFatal(ignored);
                // text is optional; geometry already emitted
            }
        }
        group.append("</g>\n");
        // Gradient definitions must precede the group that references them.
        out.append(patch.defs());
        out.append(group);
    }

    /**
     * Where the shading records come from: retained bytes read per page, or a file the native
     * side parsed once for every requested page.
     */
    private static final class Shadings {

        private static final Shadings NONE = new Shadings(null, Map.of());

        private final byte[] pdf;
        private final Map<Integer, Gradient.Records> byPage;

        private Shadings(byte[] pdf, Map<Integer, Gradient.Records> byPage) {
            this.pdf = pdf;
            this.byPage = byPage;
        }

        static Shadings ofBytes(byte[] pdf) {
            return pdf == null || pdf.length == 0 ? NONE : new Shadings(pdf, null);
        }

        /** Parse the in-memory PDF once for every requested page. */
        static Shadings ofBytes(byte[] pdf, int[] pageIndices) {
            if (pdf == null || pdf.length == 0) {
                return NONE;
            }
            try {
                return new Shadings(null,
                        Gradient.parseAll(JpdfiumLib.pageGradientsPages(pdf, pageIndices)));
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return NONE;
            }
        }

        /** Read all requested pages from the file in a single native parse. */
        static Shadings ofFile(Path path, int[] pageIndices) {
            try {
                return new Shadings(null,
                        Gradient.parseAll(JpdfiumLib.pageGradientsFile(path, pageIndices)));
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return NONE;
            }
        }

        /** Any failure leaves the page's objects on the raster path. */
        Gradient.Records forPage(int pageIndex) {
            if (pdf == null) {
                Gradient.Records records = byPage.get(pageIndex);
                return records != null ? records : new Gradient.Records();
            }
            try {
                return Gradient.parse(JpdfiumLib.pageGradients(pdf, pageIndex));
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return new Gradient.Records();
            }
        }
    }

    /** Per-channel tolerance when comparing a reported solid fill with the rendered pixel. */
    private static final int FILL_TOLERANCE = 24;

    /**
     * Per-page state: the single page render shared by every clipped patch, the clip-path counter,
     * and the shading records plus the {@code <defs>} block their references live in.
     */
    private static final class Patch {

        /** Minimum bounding-box overlap before a paint record is trusted for an object. */
        private static final float MATCH = 0.5f;

        private final PdfPage page;
        private final Options options;
        private final int pageIndex;
        private final Gradient.Records shadings;
        private final float pageWidth;
        private final float pageHeight;
        private final StringBuilder defs = new StringBuilder();
        private final Map<Integer, String> gradientIds = new HashMap<>();
        private byte[] png;
        private BufferedImage image;
        private boolean loaded;
        private String rasterRef;
        private int clipId;

        Patch(PdfPage page, Options options, int pageIndex, Gradient.Records shadings,
                float pageWidth, float pageHeight) {
            this.page = page;
            this.options = options;
            this.pageIndex = pageIndex;
            this.shadings = shadings;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
        }

        /** The accumulated {@code <defs>} body; empty when no gradient was referenced. */
        String defs() {
            return defs.length() == 0 ? "" : "<defs>\n" + defs + "</defs>\n";
        }

        /**
         * Return a {@code url(#id)} reference for the shading, writing its {@code <defs>} entry on
         * first use.
         */
        String gradientRef(Gradient g) {
            String ref = gradientIds.get(g.index);
            if (ref != null) {
                return ref;
            }
            boolean radial = g.kind == Gradient.RADIAL;
            String id = "jpdfiumGrad" + pageIndex + "_" + g.index;
            ref = "url(#" + id + ")";
            gradientIds.put(g.index, ref);
            defs.append('<').append(radial ? "radialGradient" : "linearGradient")
                    .append(" id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\"");
            if (radial) {
                // A PDF radial shading runs between two circles; SVG's focal point is the start
                // circle's centre, which is only faithful when that circle is a point.
                defs.append(" cx=\"").append(num(g.coords[3])).append("\" cy=\"")
                        .append(num(pageHeight - g.coords[4])).append("\" r=\"")
                        .append(num(g.coords[5])).append("\" fx=\"").append(num(g.coords[0]))
                        .append("\" fy=\"").append(num(pageHeight - g.coords[1])).append('"');
            } else {
                defs.append(" x1=\"").append(num(g.coords[0])).append("\" y1=\"")
                        .append(num(pageHeight - g.coords[1])).append("\" x2=\"")
                        .append(num(g.coords[3])).append("\" y2=\"")
                        .append(num(pageHeight - g.coords[4])).append('"');
            }
            defs.append(" spreadMethod=\"pad\">\n");
            int count = g.stopCount();
            for (int i = 0; i < count; i++) {
                defs.append("<stop offset=\"").append(num(g.stops[i * 4])).append("\" stop-color=\"#")
                        .append(hex(stopChannel(g.stops[i * 4 + 1])))
                        .append(hex(stopChannel(g.stops[i * 4 + 2])))
                        .append(hex(stopChannel(g.stops[i * 4 + 3]))).append("\"/>\n");
            }
            defs.append("</").append(radial ? "radialGradient" : "linearGradient").append(">\n");
            return ref;
        }

        private static int stopChannel(float value) {
            return clamp(Math.round(value * 255f), 0, 255);
        }

        /** Render the page once on first use; {@code null} when rendering is unavailable. */
        byte[] png() {
            if (!loaded) {
                loaded = true;
                try {
                    png = page.renderToBytes(options.fallbackDpi, ImageFormat.PNG);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    png = null;
                }
                if (png != null) {
                    try {
                        image = PdfImageIO.read(png);
                    } catch (Throwable ignored) {
                        NativeRuntime.rethrowFatal(ignored);
                        image = null;
                    }
                }
            }
            return png;
        }

        /**
         * True when the page paints something other than the object's reported solid fill, i.e. the
         * fill is a pattern or gradient this API cannot read back. Sampling the bounds centre keeps
         */
        boolean paintDiffers(PageObject obj, int tolerance) {
            Rect b = obj.bounds();
            if (b == null || b.width() <= 0f || b.height() <= 0f || png() == null || image == null) {
                return false;
            }
            float cx = b.x() + b.width() / 2f;
            float cy = pageHeight - (b.y() + b.height() / 2f);
            int px = clamp(Math.round(cx * image.getWidth() / pageWidth), 0, image.getWidth() - 1);
            int py = clamp(Math.round(cy * image.getHeight() / pageHeight), 0, image.getHeight() - 1);
            int argb = image.getRGB(px, py);
            int r = (argb >> 16) & 0xff;
            int g = (argb >> 8) & 0xff;
            int bl = argb & 0xff;
            return Math.abs(r - obj.fillR()) > tolerance || Math.abs(g - obj.fillG()) > tolerance
                    || Math.abs(bl - obj.fillB()) > tolerance;
        }

        int nextClipId() {
            return clipId++;
        }

        /**
         * The id of the shared full-page raster, written to {@code <defs>} on first use. Every patch
         * on the page then paints it through {@code <use>}, so the base64 payload is emitted once.
         */
        String rasterRef() {
            if (rasterRef == null) {
                byte[] bytes = png();
                if (bytes == null || bytes.length == 0) {
                    return null;
                }
                String id = "jpdfiumRaster" + pageIndex;
                defs.append("<image id=\"").append(id).append("\" x=\"0\" y=\"0\" width=\"")
                        .append(num(pageWidth)).append("\" height=\"").append(num(pageHeight))
                        .append("\" preserveAspectRatio=\"none\" href=\"data:image/png;base64,")
                        .append(Base64.getEncoder().encodeToString(bytes)).append("\"/>\n");
                rasterRef = id;
            }
            return rasterRef;
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    // ---------------------------------------------------------------- clip paths

    /**
     * Read the object's clip geometry as SVG path-data strings, one per clip subpath.
     *
     * <p>PDFium reports {@code FPDFClipPath_CountPaths == -1} when an object has
     * no clip, which is treated as an empty list.
     */
    private static List<String> objectClips(PageObject obj) {
        MemorySegment raw = obj.rawHandle();
        if (raw == null || raw.equals(MemorySegment.NULL)) {
            return List.of();
        }
        try {
            MemorySegment clip = (MemorySegment) PathBindings.FPDFPageObj_GetClipPath
                    .invokeExact(raw);
            if (clip == null || clip.equals(MemorySegment.NULL)) {
                return List.of();
            }
            int paths = (int) PathBindings.FPDFClipPath_CountPaths.invokeExact(clip);
            if (paths <= 0) {
                return List.of();
            }
            List<String> data = new ArrayList<>(paths);
            for (int i = 0; i < paths; i++) {
                String d = clipPathData(clip, i);
                if (!d.isEmpty()) {
                    data.add(d);
                }
            }
            return data;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return List.of();
        }
    }

    /**
     * Wrap content in one nested {@code <g clip-path>} per clip path. Sibling
     * paths inside a single {@code <clipPath>} would be unioned by SVG, so
     * nesting each level is what reproduces the PDF's intersection.
     */
    private static void wrapClipPaths(StringBuilder out, Patch patch, List<String> clipData,
            CharSequence content) {
        if (content == null || content.length() == 0) {
            return;
        }
        if (clipData == null || clipData.isEmpty()) {
            out.append(content);
            return;
        }
        for (String d : clipData) {
            int id = patch.nextClipId();
            out.append("<clipPath id=\"jpdfiumClip").append(id).append("\"><path d=\"")
                    .append(d).append("\"/></clipPath>\n");
            out.append("<g clip-path=\"url(#jpdfiumClip").append(id).append(")\">\n");
        }
        out.append(content);
        for (int i = 0; i < clipData.size(); i++) {
            out.append("</g>\n");
        }
    }

    /** Wrap emitted content in the object's clip geometry and blend mode. */
    private static void emitClipped(StringBuilder out, PageObject obj, Patch patch,
            CharSequence content) {
        String blend = blendFor(obj, patch);
        if (blend != null) {
            out.append("<g style=\"mix-blend-mode:").append(blend).append("\">");
        }
        wrapClipPaths(out, patch, objectClips(obj), content);
        if (blend != null) {
            out.append("</g>\n");
        }
    }

    private static void emitPathClipped(StringBuilder out, PageObject obj, Patch patch) {
        StringBuilder content = new StringBuilder(256);
        emitPath(content, obj, patch);
        emitClipped(out, obj, patch, content);
    }

    /** A rectangle expressed as path data, used to bound a raster patch that has no real clip. */
    private static String rectPathData(Rect b) {
        return "M " + num(b.x()) + " " + num(b.y()) + " H " + num(b.x() + b.width()) + " V "
                + num(b.y() + b.height()) + " H " + num(b.x()) + " Z";
    }

    // ---------------------------------------------------------------- paths

    private static void emitPath(StringBuilder out, PageObject obj, Patch patch) {
        String d = pathData(obj.rawHandle());
        if (d.isEmpty()) {
            return;
        }
        boolean fill = true;
        boolean stroke = true;
        int fillRule = 1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fillSeg = arena.allocate(JAVA_INT);
            MemorySegment strokeSeg = arena.allocate(JAVA_INT);
            int ok = (int) PathBindings.FPDFPath_GetDrawMode.invokeExact(obj.rawHandle(), fillSeg,
                    strokeSeg);
            if (ok != 0) {
                int fillMode = fillSeg.get(JAVA_INT, 0);
                int strokeMode = strokeSeg.get(JAVA_INT, 0);
                fill = fillMode != 0 || strokeMode == 2;
                stroke = strokeMode != 0;
                if (fillMode == 1) {
                    fillRule = 0; // even-odd
                }
            }
        } catch (Throwable ignored) {
            NativeRuntime.rethrowFatal(ignored);
            // keep fill+stroke defaults
        }

        if (fill && patch != null && obj.fillA() > 0) {
            // A content-stream paint record names the shading this object was filled with, so it is
            // authoritative; the pixel probe only covers objects the record cannot place.
            Gradient gradient = byPaintRecord(obj, obj.bounds(), patch.shadings);
            boolean differs = false;
            if (gradient == null) {
                differs = patch.paintDiffers(obj, FILL_TOLERANCE);
                if (differs) {
                    gradient = quadGradientFor(obj, patch);
                }
            }
            if (gradient != null) {
                out.append("<path d=\"").append(d).append("\" fill=\"")
                        .append(patch.gradientRef(gradient)).append('"');
                if (fillRule == 0) {
                    out.append(" fill-rule=\"evenodd\"");
                }
                appendPathStroke(out, obj, stroke);
                out.append("/>\n");
                return;
            }
            if (differs && patch.options.rasterFallback) {
                emitRasterPatch(out, patch, obj);
                return;
            }
        }

        out.append("<path d=\"").append(d).append('"');
        if (fill && obj.fillA() > 0) {
            out.append(" fill=\"").append(rgb(obj.fillR(), obj.fillG(), obj.fillB())).append('"');
            if (obj.fillA() < 255) {
                out.append(" fill-opacity=\"").append(num(obj.fillA() / 255f)).append('"');
            }
            if (fillRule == 0) {
                out.append(" fill-rule=\"evenodd\"");
            }
        } else {
            out.append(" fill=\"none\"");
        }
        appendPathStroke(out, obj, stroke);
        out.append("/>\n");
    }

    private static void appendPathStroke(StringBuilder out, PageObject obj, boolean stroke) {
        if (stroke && obj.strokeA() > 0) {
            out.append(" stroke=\"").append(rgb(obj.strokeR(), obj.strokeG(), obj.strokeB()))
                    .append('"');
            if (obj.strokeA() < 255) {
                out.append(" stroke-opacity=\"").append(num(obj.strokeA() / 255f)).append('"');
            }
            out.append(" stroke-width=\"").append(num(obj.strokeWidth() > 0 ? obj.strokeWidth() : 1f))
                    .append('"');
            appendStrokeDetail(out, obj.rawHandle());
        }
    }

    /**
     * Pick the shading that paints this object. A content-stream paint record wins because it knows
     * the shape the pattern was selected for; otherwise the pattern's unit square is matched against
     */
    private static Gradient quadGradientFor(PageObject obj, Patch patch) {
        if (patch == null || patch.shadings.gradients.isEmpty()) {
            return null;
        }
        Rect b = obj.bounds();
        if (b == null || b.width() <= 0f || b.height() <= 0f) {
            return null;
        }
        List<Gradient> gradients = patch.shadings.gradients;
        float bx0 = b.x();
        float by0 = b.y();
        float bx1 = b.x() + b.width();
        float by1 = b.y() + b.height();
        float area = (bx1 - bx0) * (by1 - by0);
        Gradient best = null;
        float bestOverlap = 0f;
        for (Gradient g : gradients) {
            if (g.kind == Gradient.RADIAL && g.coords[2] != 0f) {
                // An inner circle with a radius has no faithful SVG radial mapping.
                continue;
            }
            float gx0 = g.minX();
            float gy0 = g.minY();
            float gx1 = g.maxX();
            float gy1 = g.maxY();
            float ix0 = Math.max(bx0, gx0);
            float iy0 = Math.max(by0, gy0);
            float ix1 = Math.min(bx1, gx1);
            float iy1 = Math.min(by1, gy1);
            if (ix1 <= ix0 || iy1 <= iy0) {
                continue;
            }
            float intersection = (ix1 - ix0) * (iy1 - iy0);
            float union = area + (gx1 - gx0) * (gy1 - gy0) - intersection;
            float overlap = union > 0f ? intersection / union : 0f;
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = g;
            }
        }
        return bestOverlap >= 0.5f ? best : null;
    }

    /**
     * Resolve the object's gradient through the content-stream paint record whose bounding box best
     * matches, which also covers pattern matrices that are not the object's bounding box.
     */
    private static Gradient byPaintRecord(PageObject obj, Rect b, Gradient.Records shadings) {
        int best = -1;
        float bestOverlap = 0f;
        for (Gradient.Paint paint : shadings.paints) {
            if (paint.gradient < 0 || paint.gradient >= shadings.gradients.size()) {
                continue;
            }
            float overlap = overlap(paint, b);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = paint.gradient;
            }
        }
        return bestOverlap >= Patch.MATCH ? shadings.gradients.get(best) : null;
    }

    /** CSS {@code mix-blend-mode} value for the object, or {@code null} when it composites normally. */
    private static String blendFor(PageObject obj, Patch patch) {
        if (patch == null || patch.shadings.paints.isEmpty()) {
            return null;
        }
        Rect b = obj.bounds();
        if (b == null) {
            return null;
        }
        for (Gradient.Paint paint : patch.shadings.paints) {
            if (paint.blend != null && overlap(paint, b) >= Patch.MATCH) {
                return cssBlend(paint.blend);
            }
        }
        return null;
    }

    /** Intersection over union between a paint record's device box and an object's device bounds. */
    private static float overlap(Gradient.Paint paint, Rect b) {
        float ix0 = Math.max(b.x(), paint.minX());
        float iy0 = Math.max(b.y(), paint.minY());
        float ix1 = Math.min(b.x() + b.width(), paint.maxX());
        float iy1 = Math.min(b.y() + b.height(), paint.maxY());
        if (ix1 <= ix0 || iy1 <= iy0) {
            return 0f;
        }
        float intersection = (ix1 - ix0) * (iy1 - iy0);
        float area = b.width() * b.height();
        float paintArea = (paint.maxX() - paint.minX()) * (paint.maxY() - paint.minY());
        float union = area + paintArea - intersection;
        return union > 0f ? intersection / union : 0f;
    }

    /** PDF blend names are the CSS names in kebab case ({@code ColorDodge} → {@code color-dodge}). */
    private static String cssBlend(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('-');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static void appendStrokeDetail(StringBuilder out, MemorySegment rawPath) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(JAVA_INT);
            if ((int) PathBindings.FPDFPageObj_GetLineCap.invokeExact(rawPath, slot) != 0) {
                switch (slot.get(JAVA_INT, 0)) {
                    case 0 -> out.append(" stroke-linecap=\"butt\"");
                    case 1 -> out.append(" stroke-linecap=\"round\"");
                    case 2 -> out.append(" stroke-linecap=\"square\"");
                    default -> { }
                }
            }
            if ((int) PathBindings.FPDFPageObj_GetLineJoin.invokeExact(rawPath, slot) != 0) {
                switch (slot.get(JAVA_INT, 0)) {
                    case 0 -> out.append(" stroke-linejoin=\"miter\"");
                    case 1 -> out.append(" stroke-linejoin=\"round\"");
                    case 2 -> out.append(" stroke-linejoin=\"bevel\"");
                    default -> { }
                }
            }
            int dashCount = (int) PathBindings.FPDFPageObj_GetDashCount.invokeExact(rawPath);
            if (dashCount > 0) {
                MemorySegment dashArray = arena.allocate(JAVA_FLOAT, dashCount);
                if ((int) PathBindings.FPDFPageObj_GetDashArray.invokeExact(rawPath, dashArray,
                        (long) dashCount) != 0) {
                    StringBuilder dash = new StringBuilder();
                    for (int i = 0; i < dashCount; i++) {
                        if (i > 0) {
                            dash.append(' ');
                        }
                        dash.append(num(dashArray.getAtIndex(JAVA_FLOAT, i)));
                    }
                    out.append(" stroke-dasharray=\"").append(dash).append('"');
                    MemorySegment phase = arena.allocate(JAVA_FLOAT);
                    if ((int) PathBindings.FPDFPageObj_GetDashPhase.invokeExact(rawPath, phase) != 0) {
                        out.append(" stroke-dashoffset=\"").append(num(phase.get(JAVA_FLOAT, 0)))
                                .append('"');
                    }
                }
            }
        } catch (Throwable ignored) {
            NativeRuntime.rethrowFatal(ignored);
            // stroke detail is optional
        }
    }

    /** Provides the segment list of one path without caring which PDFium API yielded it. */
    @FunctionalInterface
    private interface SegmentSource {
        MemorySegment segment(int index) throws Throwable;
    }

    /** Convert a PDFium path object into an SVG {@code d} attribute. */
    private static String pathData(MemorySegment path) {
        if (path == null || path.equals(MemorySegment.NULL)) {
            return "";
        }
        try {
            int count = (int) PathBindings.FPDFPath_CountSegments.invokeExact(path);
            return buildPathData(count,
                    i -> (MemorySegment) PathBindings.FPDFPath_GetPathSegment.invokeExact(path, i));
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return "";
        }
    }

    /** Convert one clip-path subpath into an SVG {@code d} attribute (page space). */
    private static String clipPathData(MemorySegment clip, int pathIndex) {
        try {
            int count = (int) PathBindings.FPDFClipPath_CountPathSegments.invokeExact(clip, pathIndex);
            if (count <= 0) {
                return "";
            }
            return buildPathData(count, i -> (MemorySegment) PathBindings.FPDFClipPath_GetPathSegment
                    .invokeExact(clip, pathIndex, i));
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return "";
        }
    }

    /**
     * Render a segment list as SVG path data. Points are mapped through
     * {@code (x*scale + tx, y*scale + ty)} so the same code serves object paths
     * (identity) and glyph outlines (scaled from font units).
     */
    private static String buildPathData(int count, SegmentSource source, float scale, float tx,
            float ty) {
        StringBuilder d = new StringBuilder(128);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment px = arena.allocate(JAVA_FLOAT);
            MemorySegment py = arena.allocate(JAVA_FLOAT);
            float[] pending = new float[6];
            int pendingPoints = 0;
            for (int i = 0; i < count; i++) {
                MemorySegment seg = source.segment(i);
                if (seg == null || seg.equals(MemorySegment.NULL)) {
                    break;
                }
                if ((int) PathBindings.FPDFPathSegment_GetPoint.invokeExact(seg, px, py) == 0) {
                    break;
                }
                float x = px.get(JAVA_FLOAT, 0) * scale + tx;
                float y = py.get(JAVA_FLOAT, 0) * scale + ty;
                int type = (int) PathBindings.FPDFPathSegment_GetType.invokeExact(seg);
                if (type == PathBindings.FPDF_SEGMENT_MOVETO) {
                    flushCurve(d, pending, pendingPoints);
                    pendingPoints = 0;
                    d.append('M').append(num(x)).append(' ').append(num(y));
                } else if (type == PathBindings.FPDF_SEGMENT_BEZIERTO) {
                    if (pendingPoints <= 4) {
                        pending[pendingPoints++] = x;
                        pending[pendingPoints++] = y;
                    }
                    if (pendingPoints == 6) {
                        d.append('C').append(num(pending[0])).append(' ').append(num(pending[1]))
                                .append(' ').append(num(pending[2])).append(' ')
                                .append(num(pending[3])).append(' ').append(num(pending[4]))
                                .append(' ').append(num(pending[5]));
                        pendingPoints = 0;
                    }
                } else {
                    flushCurve(d, pending, pendingPoints);
                    pendingPoints = 0;
                    d.append('L').append(num(x)).append(' ').append(num(y));
                }
                if ((int) PathBindings.FPDFPathSegment_GetClose.invokeExact(seg) != 0) {
                    flushCurve(d, pending, pendingPoints);
                    pendingPoints = 0;
                    d.append('Z');
                }
            }
            flushCurve(d, pending, pendingPoints);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return "";
        }
        return d.toString();
    }

    private static String buildPathData(int count, SegmentSource source) {
        return buildPathData(count, source, 1f, 0f, 0f);
    }

    private static void flushCurve(StringBuilder d, float[] pending, int pendingPoints) {
        // Beziers arrive as control/control/endpoint runs; anything left over is drawn as lines so
        // geometry is never dropped.
        for (int i = 0; i + 1 < pendingPoints; i += 2) {
            d.append('L').append(num(pending[i])).append(' ').append(num(pending[i + 1]));
        }
    }

    // ---------------------------------------------------------------- forms

    private static void emitForm(StringBuilder out, MemorySegment form, int depth, Patch patch) {
        if (depth > 4) {
            return;
        }
        int count;
        try {
            count = (int) PathBindings.FPDFFormObj_CountObjects.invokeExact(form);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return;
        }
        for (int i = 0; i < count; i++) {
            MemorySegment child;
            try {
                child = (MemorySegment) PathBindings.FPDFFormObj_GetObject.invokeExact(form, (long) i);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                return;
            }
            if (child == null || child.equals(MemorySegment.NULL)) {
                continue;
            }
            int type;
            try {
                type = (int) stirling.software.jpdfium.panama.PageEditBindings.FPDFPageObj_GetType
                        .invokeExact(child);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                continue;
            }
            if (type == 2) {
                PageObject childObj = readChildObject(child, i, PageObjectType.PATH);
                if (childObj != null) {
                    emitPath(out, childObj, patch);
                }
            } else if (type == 3 || type == 4) {
                // Form XObject images are not part of the page-level image
                // extraction, and form shading objects have no vector mapping
                // here. Cover the child with the page raster when allowed; text
                // inside forms is emitted separately by the text extractor.
                if (type == 3 && !patch.options.includeImages) {
                    continue;
                }
                if (patch.options.rasterFallback) {
                    PageObject childObj =
                            readChildObject(child, i, PageObjectType.fromCode(type));
                    if (childObj != null) {
                        emitRasterPatch(out, patch, childObj);
                    }
                }
            } else if (type == 5) {
                emitForm(out, child, depth + 1, patch);
            }
        }
    }

    /** Minimal page-object view of a form child, so the shared path/raster emitters can run. */
    private static PageObject readChildObject(MemorySegment child, int index, PageObjectType type) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment r = arena.allocate(JAVA_INT);
            MemorySegment g = arena.allocate(JAVA_INT);
            MemorySegment b = arena.allocate(JAVA_INT);
            MemorySegment a = arena.allocate(JAVA_INT);
            int fillA = 0;
            int fillR = 0;
            int fillG = 0;
            int fillB = 0;
            if ((int) stirling.software.jpdfium.panama.PageEditBindings.FPDFPageObj_GetFillColor
                    .invokeExact(child, r, g, b, a) != 0) {
                fillR = r.get(JAVA_INT, 0);
                fillG = g.get(JAVA_INT, 0);
                fillB = b.get(JAVA_INT, 0);
                fillA = a.get(JAVA_INT, 0);
            }
            int strokeA = 0;
            int strokeR = 0;
            int strokeG = 0;
            int strokeB = 0;
            if ((int) stirling.software.jpdfium.panama.PageEditBindings.FPDFPageObj_GetStrokeColor
                    .invokeExact(child, r, g, b, a) != 0) {
                strokeR = r.get(JAVA_INT, 0);
                strokeG = g.get(JAVA_INT, 0);
                strokeB = b.get(JAVA_INT, 0);
                strokeA = a.get(JAVA_INT, 0);
            }
            MemorySegment width = arena.allocate(JAVA_FLOAT);
            float strokeWidth = 0f;
            if ((int) stirling.software.jpdfium.panama.PageEditBindings.FPDFPageObj_GetStrokeWidth
                    .invokeExact(child, width) != 0) {
                strokeWidth = width.get(JAVA_FLOAT, 0);
            }
            MemorySegment l = arena.allocate(JAVA_FLOAT);
            MemorySegment btm = arena.allocate(JAVA_FLOAT);
            MemorySegment right = arena.allocate(JAVA_FLOAT);
            MemorySegment top = arena.allocate(JAVA_FLOAT);
            Rect bounds = new Rect(0f, 0f, 0f, 0f);
            if ((int) stirling.software.jpdfium.panama.PageEditBindings.FPDFPageObj_GetBounds
                    .invokeExact(child, l, btm, right, top) != 0) {
                bounds = new Rect(l.get(JAVA_FLOAT, 0), btm.get(JAVA_FLOAT, 0),
                        right.get(JAVA_FLOAT, 0) - l.get(JAVA_FLOAT, 0),
                        top.get(JAVA_FLOAT, 0) - btm.get(JAVA_FLOAT, 0));
            }
            return new PageObject(index, type, bounds, new float[] {1, 0, 0, 1, 0, 0},
                    false, fillR, fillG, fillB, fillA, strokeR, strokeG, strokeB, strokeA,
                    strokeWidth, List.of(), child);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    // ---------------------------------------------------------------- images

    private static void emitImage(StringBuilder out, PageObject obj, List<ExtractedImage> images) {
        ExtractedImage match = null;
        for (ExtractedImage img : images) {
            if (img.objectIndex() == obj.index()) {
                match = img;
                break;
            }
        }
        if (match == null) {
            return;
        }
        byte[] png = null;
        try {
            BufferedImage image = match.toBufferedImage();
            if (image != null) {
                png = PdfImageIO.writeToBytes(image, "png");
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            // fall through: a raw JPEG can still be embedded verbatim
        }
        String mime = "png";
        if (png == null && match.rawBytes() != null && match.rawBytes().length > 0) {
            String ext = match.suggestedExtension();
            if (".jpg".equals(ext)) {
                png = match.rawBytes();
                mime = "jpeg";
            }
        }
        if (png == null || png.length == 0) {
            return;
        }
        Rect b = match.bounds() != null ? match.bounds() : obj.bounds();
        out.append("<image x=\"").append(num(b.x())).append("\" y=\"").append(num(b.y()))
                .append("\" width=\"").append(num(b.width())).append("\" height=\"")
                .append(num(b.height())).append("\" preserveAspectRatio=\"none\" href=\"data:image/")
                .append(mime).append(";base64,").append(Base64.getEncoder().encodeToString(png))
                .append("\"/>\n");
    }

    // ---------------------------------------------------------------- raster fallback

    /**
     * Cover one object with a clipped render of the whole page. Vectors outside the clip are
     * unaffected; only the object's own box comes from the raster.
     */
    private static void emitRasterPatch(StringBuilder out, Patch patch, PageObject obj) {
        String ref = patch.rasterRef();
        if (ref == null) {
            return;
        }
        List<String> clips = objectClips(obj);
        if (clips.isEmpty()) {
            Rect b = obj.bounds();
            if (b == null) {
                return;
            }
            // No PDF clip on the object (typical for a shading): bound the patch to its own box.
            clips = List.of(rectPathData(b));
        }
        StringBuilder content = new StringBuilder(256);
        // The root group flips y so every coordinate stays in PDF page space, but an <image> paints
        // its own top row along local +y, which that flip would mirror. Cancel the flip for the
        content.append("<g transform=\"translate(0 ").append(num(patch.pageHeight))
                .append(") scale(1 -1)\">\n");
        content.append("<use href=\"#").append(ref).append("\"/>\n");
        content.append("</g>\n");
        wrapClipPaths(out, patch, clips, content);
    }

    // ---------------------------------------------------------------- text

    /**
     * Emit text as one {@code <text>} element per run of characters sharing
     * font, size and baseline.
     *
     * <p>{@link TextChar#originY()} is the baseline, so it is used directly as
     * the SVG {@code y}; the glyph box's lower edge would shift descenders.
     */
    private static void emitText(StringBuilder out, PdfDocument doc, int pageIndex,
            List<PageObject> objects) {
        PageText text = PdfTextExtractor.extractPage(doc, pageIndex);
        List<TextChar> chars = text.chars();
        int start = 0;
        while (start < chars.size()) {
            TextChar first = chars.get(start);
            if (first.isNewline()) {
                start++;
                continue;
            }
            String font = first.fontName();
            float size = first.fontSize();
            float baseline = first.originY();
            float runBaseline = baseline;
            float previousX = first.originX();
            int end = start;
            StringBuilder run = new StringBuilder();
            while (end < chars.size()) {
                TextChar c = chars.get(end);
                if (c.isNewline() || !sameRun(c, font, size, baseline, previousX)) {
                    break;
                }
                run.append(c.toText());
                runBaseline = Math.max(runBaseline, c.originY());
                previousX = c.originX();
                end++;
            }
            if (run.length() > 0) {
                out.append("<text x=\"").append(num(first.originX())).append("\" y=\"")
                        .append(num(runBaseline)).append("\" font-family=\"")
                        .append(escape(font == null ? "sans-serif" : font)).append("\" font-size=\"")
                        .append(num(size)).append("\" fill=\"")
                        .append(textColor(objects, first)).append("\">")
                        .append(escape(run.toString())).append("</text>\n");
            }
            start = end > start ? end : start + 1;
        }
    }

    private static boolean sameRun(TextChar c, String font, float size,
            float baseline, float previousX) {
        if (c.fontName() == null || !c.fontName().equals(font)
                || Math.abs(c.fontSize() - size) >= 0.01f
                || Math.abs(c.originY() - baseline) >= Math.max(0.5f, size * 0.2f)) {
            return false;
        }
        // A column jump or a backwards origin means a new run even when the
        // font and baseline match; wide inter-word spaces stay in the run.
        float em = Math.max(size, 1f);
        float advance = c.originX() - previousX;
        return advance <= em * 2.5f && advance >= -em * 0.5f;
    }

    private static String textColor(List<PageObject> objects, TextChar c) {
        for (PageObject obj : objects) {
            if (obj.type() != PageObjectType.TEXT || obj.bounds() == null) {
                continue;
            }
            Rect b = obj.bounds();
            if (c.x() >= b.x() && c.x() <= b.x() + b.width() && c.y() >= b.y()
                    && c.y() <= b.y() + b.height() && obj.fillA() > 0) {
                return rgb(obj.fillR(), obj.fillG(), obj.fillB());
            }
        }
        return "#000000";
    }

    /**
     * Text as vector glyph outlines. Each run is shaped with HarfBuzz through
     * {@link FontLib} and the per-glyph contours are read from PDFium's
     * {@code FPDFFont_GetGlyphPath}. Runs that cannot be shaped or outlined
     * fall back to a plain {@code <text>} element.
     */
    private static void emitTextOutlines(StringBuilder out, PdfDocument doc, int pageIndex,
            MemorySegment rawPage, List<PageObject> objects) {
        PageText text = PdfTextExtractor.extractPage(doc, pageIndex);
        List<TextChar> chars = text.chars();
        MemorySegment textPage;
        try {
            textPage = (MemorySegment) TextPageBindings.FPDFText_LoadPage.invokeExact(rawPage);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            textPage = MemorySegment.NULL;
        }
        if (textPage == null || textPage.equals(MemorySegment.NULL)) {
            emitText(out, doc, pageIndex, objects);
            return;
        }
        try {
            Map<Long, byte[]> fontPrograms = new HashMap<>();
            int start = 0;
            while (start < chars.size()) {
                TextChar first = chars.get(start);
                if (first.isNewline()) {
                    start++;
                    continue;
                }
                String font = first.fontName();
                float size = first.fontSize();
                float baseline = first.originY();
                float runBaseline = baseline;
                float previousX = first.originX();
                int end = start;
                StringBuilder run = new StringBuilder();
                while (end < chars.size()) {
                    TextChar c = chars.get(end);
                    if (c.isNewline() || !sameRun(c, font, size, baseline, previousX)) {
                        break;
                    }
                    run.append(c.toText());
                    runBaseline = Math.max(runBaseline, c.originY());
                    previousX = c.originX();
                    end++;
                }
                if (run.length() > 0) {
                    emitTextRunOutline(out, objects, fontPrograms, textPage, run.toString(), first,
                            chars.get(end - 1), size, runBaseline);
                }
                start = end > start ? end : start + 1;
            }
        } finally {
            try {
                TextPageBindings.FPDFText_ClosePage.invokeExact(textPage);
            } catch (Throwable ignored) {
                NativeRuntime.rethrowFatal(ignored);
                // best-effort close
            }
        }
    }

    private static void emitTextRunOutline(StringBuilder out, List<PageObject> objects,
            Map<Long, byte[]> fontPrograms, MemorySegment textPage, String runText, TextChar first,
            TextChar last, float size, float baseline) {
        String data = outlineRunData(objects, fontPrograms, textPage, runText, first, last, size);
        if (data.isEmpty()) {
            out.append("<text x=\"").append(num(first.originX())).append("\" y=\"")
                    .append(num(baseline)).append("\" font-family=\"")
                    .append(escape(first.fontName() == null ? "sans-serif" : first.fontName()))
                    .append("\" font-size=\"").append(num(size)).append("\" fill=\"")
                    .append(textColor(objects, first)).append("\">")
                    .append(escape(runText)).append("</text>\n");
            return;
        }
        out.append("<path d=\"").append(data).append("\" fill=\"")
                .append(textColor(objects, first)).append("\"/>\n");
    }

    private static String outlineRunData(List<PageObject> objects, Map<Long, byte[]> fontPrograms,
            MemorySegment textPage, String runText, TextChar first, TextChar last, float size) {
        MemorySegment font = textFont(objects, first);
        if (font == null || font.equals(MemorySegment.NULL)) {
            return "";
        }
        if (!Float.isFinite(size) || size <= 0f) {
            return "";
        }
        byte[] program = fontPrograms.computeIfAbsent(font.address(), key -> fontProgram(font));
        if (program == null || program.length == 0) {
            return "";
        }
        float[] origin = charOrigin(textPage, first.index());
        float[] endOrigin = charOrigin(textPage, last.index());
        if (origin == null || endOrigin == null) {
            return "";
        }
        // Outlines are emitted unrotated; a run whose baseline moves is rotated or skewed.
        if (Math.abs(endOrigin[1] - origin[1]) > 0.5f) {
            return "";
        }
        List<ShapedGlyph> glyphs;
        try {
            glyphs = FontLib.shapeText(program, runText, size);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return "";
        }
        if (glyphs == null || glyphs.isEmpty()) {
            return "";
        }
        StringBuilder d = new StringBuilder(256);
        float penX = origin[0];
        float penY = origin[1];
        for (ShapedGlyph glyph : glyphs) {
            d.append(glyphPathData(font, glyph.glyphId(), size, penX + glyph.offsetX(),
                    penY + glyph.offsetY()));
            penX += glyph.advanceX();
            penY += glyph.advanceY();
        }
        return d.toString();
    }

    private static String glyphPathData(MemorySegment font, int glyphId, float size, float tx,
            float ty) {
        if (glyphId < 0) {
            return "";
        }
        MemorySegment glyph;
        try {
            glyph = (MemorySegment) PathBindings.FPDFFont_GetGlyphPath.invokeExact(font, glyphId, size);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return "";
        }
        if (glyph == null || glyph.equals(MemorySegment.NULL)) {
            return "";
        }
        int count;
        try {
            count = (int) PathBindings.FPDFGlyphPath_CountGlyphSegments.invokeExact(glyph);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return "";
        }
        if (count <= 0) {
            return "";
        }
        // The outline arrives in em units regardless of the size argument, so scale by the size.
        return buildPathData(count, index -> (MemorySegment) PathBindings.FPDFGlyphPath_GetGlyphPathSegment
                .invokeExact(glyph, index), size, tx, ty);
    }

    /** Read the embedded font program, or {@code null} when PDFium has no data for the font. */
    private static byte[] fontProgram(MemorySegment font) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment probe = arena.allocate(1);
            MemorySegment outLen = arena.allocate(JAVA_LONG);
            if ((int) PathBindings.FPDFFont_GetFontData.invokeExact(font, probe, 0L, outLen) == 0) {
                return null;
            }
            long len = outLen.get(JAVA_LONG, 0);
            if (len <= 0 || len > Integer.MAX_VALUE) {
                return null;
            }
            MemorySegment buffer = arena.allocate(len);
            if ((int) PathBindings.FPDFFont_GetFontData.invokeExact(font, buffer, len, outLen) == 0) {
                return null;
            }
            return buffer.asSlice(0, len).toArray(JAVA_BYTE);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    private static MemorySegment textFont(List<PageObject> objects, TextChar c) {
        for (PageObject obj : objects) {
            if (obj.type() != PageObjectType.TEXT || obj.bounds() == null) {
                continue;
            }
            Rect b = obj.bounds();
            if (c.x() >= b.x() && c.x() <= b.x() + b.width() && c.y() >= b.y()
                    && c.y() <= b.y() + b.height()) {
                try {
                    return (MemorySegment) PathBindings.FPDFTextObj_GetFont
                            .invokeExact(obj.rawHandle());
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    return null;
                }
            }
        }
        return null;
    }

    private static float[] charOrigin(MemorySegment textPage, int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment x = arena.allocate(JAVA_FLOAT);
            MemorySegment y = arena.allocate(JAVA_FLOAT);
            if ((int) TextPageBindings.FPDFText_GetCharOrigin.invokeExact(textPage, index, x, y)
                    != 0) {
                return new float[] {x.get(JAVA_FLOAT, 0), y.get(JAVA_FLOAT, 0)};
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            // fall through
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static String rgb(int r, int g, int b) {
        return "#" + hex(r) + hex(g) + hex(b);
    }

    private static String hex(int value) {
        int v = Math.max(0, Math.min(255, value));
        return String.format(Locale.ROOT, "%02x", v);
    }

    /**
     * Compact fixed-point number: three decimals, no exponent, trailing zeros
     * trimmed. This deliberately avoids {@link String#format} on the hot path.
     */
    private static String num(float value) {
        if (!Float.isFinite(value)) {
            return "0";
        }
        double abs = Math.abs((double) value);
        if (abs >= 1.0e9) {
            // Coordinates never reach this range in practice; keep the fallback
            // correct for callers that pass arbitrary values.
            String formatted = String.format(Locale.ROOT, "%.3f", value);
            int dot = formatted.indexOf('.');
            if (dot < 0) {
                return formatted;
            }
            int end = formatted.length();
            while (end > dot + 1 && formatted.charAt(end - 1) == '0') {
                end--;
            }
            if (end == dot + 1) {
                end = dot;
            }
            return formatted.substring(0, end);
        }
        long scaled = Math.round(abs * 1000.0);
        if (scaled == 0) {
            return "0";
        }
        StringBuilder out = new StringBuilder(12);
        if (value < 0) {
            out.append('-');
        }
        out.append(scaled / 1000);
        int fraction = (int) (scaled % 1000);
        if (fraction != 0) {
            out.append('.');
            if (fraction < 100) {
                out.append('0');
            }
            if (fraction < 10) {
                out.append('0');
            }
            out.append(fraction);
            while (out.charAt(out.length() - 1) == '0') {
                out.setLength(out.length() - 1);
            }
        }
        return out.toString();
    }

    private static String escape(String value) {
        StringBuilder b = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
