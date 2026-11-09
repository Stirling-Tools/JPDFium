package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.internal.ImageCodecs;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.panama.ImageObjBindings;
import stirling.software.jpdfium.panama.JpdfiumH;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RustBridgeBindings;
import stirling.software.jpdfium.panama.Symbols;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.zip.Deflater;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * In-process image downsampling and JPEG re-encoding: images above a DPI or
 * pixel-edge limit are resampled from their native pixels, and 8-bit
 * gray/RGB images are re-encoded as JPEG when that produces a smaller stream,
 * all via PDFium with no external binary.
 */
public final class PdfImageOptimizer {

    private PdfImageOptimizer() {}

    private static final int FPDF_PAGEOBJ_IMAGE = 3;
    private static final int FPDF_PAGEOBJ_FORM = 5;
    private static final int MAX_FORM_DEPTH = 8;
    private static final int FPDF_BITMAP_GRAY = 1;
    private static final int FPDF_BITMAP_BGR = 2;
    private static final int FPDF_BITMAP_BGRx = 3;
    private static final int FPDF_BITMAP_BGRA = 4;

    // Hard cap on decoded pixels: bounds peak heap per untrusted image (~400 MB at 100M px).
    private static final long MAX_PIXELS = 100_000_000L;

    // Native unpack formats: 1=gray8, 2=rgb24, 3=cmyk32.
    private static final int RUST_FMT_GRAY8 = 1;
    private static final int RUST_FMT_RGB24 = 2;
    private static final int RUST_FMT_CMYK32 = 3;

    // Native unpack kernel present in the loaded bridge; the stub also resolves it but returns -99.
    private static final boolean NATIVE_UNPACK =
            Symbols.find("jpdfium_rust_unpack_pixels").isPresent();

    // FPDF_FILEACCESS carries `unsigned long` for the length and for the
    // callback's position and size. That is 8 bytes on Linux and macOS but 4 on
    // Windows (LLP64), so the layout and a matching callback variant are chosen
    // from the platform: an upcall stub has to agree with the C prototype or it
    // reads the arguments off the stack with the wrong width.
    private static final MemoryLayout C_LONG_LAYOUT =
            Linker.nativeLinker().canonicalLayouts().get("long");
    private static final boolean LONG_CARRIER = C_LONG_LAYOUT.byteSize() != 4;

    // Resolved once; the JPEG loader binds the handle to each image's file access.
    private static final MethodHandle GET_BLOCK = getBlockHandle();
    private static final FunctionDescriptor GET_BLOCK_DESCRIPTOR =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, C_LONG_LAYOUT, ADDRESS, C_LONG_LAYOUT);

    private static MethodHandle getBlockHandle() {
        try {
            return MethodHandles.lookup().findVirtual(
                    JpegFileAccess.class,
                    LONG_CARRIER ? "getBlock" : "getBlockInt",
                    LONG_CARRIER
                            ? MethodType.methodType(int.class, MemorySegment.class, long.class,
                                    MemorySegment.class, long.class)
                            : MethodType.methodType(int.class, MemorySegment.class, int.class,
                                    MemorySegment.class, int.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final int CS_DEVICE_GRAY = 1;
    private static final int CS_DEVICE_RGB = 2;
    private static final int CS_DEVICE_CMYK = 3;

    // FPDF_IMAGEOBJ_METADATA: width, height, hdpi, vdpi, bpp, colorspace, marked_content_id
    private static final int META_BYTES = 32;

    /** Source pixels decoded at the image's native resolution. */
    private record ImageSource(BufferedImage image, int width, int height) {}

    /**
     * True when the native image-edit bindings required for optimization exist.
     */
    public static boolean isSupported() {
        return PageEditBindings.FPDFImageObj_SetBitmap != null
                && PageEditBindings.FPDFBitmap_CreateEx != null
                && ImageObjBindings.FPDFImageObj_GetBitmap != null;
    }

    /** True if any page holds an image object; {@link #optimize} cannot change a document without one. */
    public static boolean hasImages(PdfDocument doc) {
        if (!isSupported()) return false;
        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();
                int objCount;
                try {
                    objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    continue;
                }
                for (int i = 0; i < objCount; i++) {
                    try {
                        MemorySegment obj =
                                (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                        if (obj.equals(MemorySegment.NULL)) continue;
                        if (containsImage(obj, 0)) return true;
                    } catch (Throwable t) {
                        NativeRuntime.rethrowFatal(t);
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsImage(MemorySegment obj, int depth) throws Throwable {
        int type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
        if (type == FPDF_PAGEOBJ_IMAGE) return true;
        if (type != FPDF_PAGEOBJ_FORM || depth >= MAX_FORM_DEPTH
                || !PageEditBindings.formTraversalAvailable()) {
            return false;
        }
        int children = (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(obj);
        if (children < 0) {
            throw new JPDFiumException("FPDFFormObj_CountObjects returned " + children);
        }
        for (int i = 0; i < children; i++) {
            MemorySegment child = PageEditBindings.formObject(obj, i);
            if (child == null || child.equals(MemorySegment.NULL)) continue;
            if (containsImage(child, depth + 1)) return true;
        }
        return false;
    }

    /**
     * Downsamples images above {@code maxImageDpi}; images at or below it are left byte-for-byte
     * untouched, and lossy re-encoding without downsampling never happens.
     *
     * @param doc document to modify in place
     * @param maxImageDpi images above this effective DPI are downsampled; {@code <= 0} disables it
     * @return number of image objects rewritten
     */
    public static int optimize(PdfDocument doc, int maxImageDpi) {
        return optimize(doc, maxImageDpi, -1);
    }

    /**
     * Downsamples images above {@code maxImageDpi}; JPEG originals are re-encoded as JPEG at
     * {@code jpegQuality} ({@code <= 0} keeps the flate path). Returns the number rewritten.
     */
    public static int optimize(PdfDocument doc, int maxImageDpi, int jpegQuality) {
        return optimize(doc, maxImageDpi, jpegQuality, 0, false, false, false);
    }

    /**
     * Full image pass: downsample by effective DPI and/or longest-edge pixel cap, then
     * re-encode as JPEG at {@code jpegQuality} when that is smaller than the current stream.
     *
     * @param maxImageDpi images above this effective DPI are downsampled; {@code <= 0} disables it
     * @param jpegQuality JPEG quality for re-encoding; {@code <= 0} disables JPEG output
     * @param maxImageEdgePx images whose longest edge exceeds this many pixels are downsampled
     *     regardless of DPI; {@code <= 0} disables it. This is the only usable size signal for
     *     comic pages laid out as 1 image pixel = 1 PDF point, which report 72 DPI everywhere.
     * @param convertPngToJpeg re-encode 8-bit gray/RGB images that are not already JPEG
     * @return number of image objects rewritten
     */
    public static int optimize(PdfDocument doc, int maxImageDpi, int jpegQuality,
            int maxImageEdgePx, boolean convertPngToJpeg) {
        return optimize(doc, maxImageDpi, jpegQuality, maxImageEdgePx, convertPngToJpeg, false, true);
    }

    /**
     * Full image pass with an explicit JPEG scan mode.
     *
     * @param jpegProgressive write progressive JPEG instead of baseline
     */
    public static int optimize(PdfDocument doc, int maxImageDpi, int jpegQuality,
            int maxImageEdgePx, boolean convertPngToJpeg, boolean jpegProgressive) {
        return optimize(doc, maxImageDpi, jpegQuality, maxImageEdgePx, convertPngToJpeg,
                jpegProgressive, true);
    }

    private static int optimize(PdfDocument doc, int maxImageDpi, int jpegQuality,
            int maxImageEdgePx, boolean convertPngToJpeg, boolean jpegProgressive,
            boolean allowQualityOnly) {
        if (!isSupported()) return 0;
        if (maxImageDpi <= 0 && maxImageEdgePx <= 0 && jpegQuality <= 0) return 0;
        MemorySegment rawDoc = doc.rawHandle();
        int rewritten = 0;

        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();
                int objCount;
                try {
                    objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    continue;
                }

                boolean pageChanged = false;
                for (int i = 0; i < objCount; i++) {
                    MemorySegment obj;
                    try {
                        obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                    } catch (Throwable t) {
                        NativeRuntime.rethrowFatal(t);
                        continue;
                    }
                    if (obj.equals(MemorySegment.NULL)) continue;
                    if (optimizeTree(rawDoc, rawPage, obj, maxImageDpi, jpegQuality,
                            maxImageEdgePx, convertPngToJpeg, jpegProgressive, allowQualityOnly, 0)) {
                        pageChanged = true;
                        rewritten++;
                    }
                }

                if (pageChanged) {
                    int ok;
                    try {
                        ok = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage);
                    } catch (Throwable t) {
                        throw new JPDFiumException("FPDFPage_GenerateContent failed", t);
                    }
                    if (ok == 0) throw new JPDFiumException("FPDFPage_GenerateContent failed");
                }
            }
        }
        return rewritten;
    }

    private static boolean optimizeTree(MemorySegment rawDoc, MemorySegment rawPage,
            MemorySegment obj, int maxImageDpi, int jpegQuality, int maxImageEdgePx,
            boolean convertPngToJpeg, boolean jpegProgressive, boolean allowQualityOnly,
            int depth) {
        try {
            int type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
            if (type == FPDF_PAGEOBJ_IMAGE) {
                return optimizeImage(rawDoc, rawPage, obj, maxImageDpi, jpegQuality,
                        maxImageEdgePx, convertPngToJpeg, jpegProgressive, allowQualityOnly);
            }
            if (type != FPDF_PAGEOBJ_FORM || depth >= MAX_FORM_DEPTH
                    || !PageEditBindings.formConversionAvailable()) {
                return false;
            }
            int children = (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(obj);
            if (children < 0) {
                throw new JPDFiumException("FPDFFormObj_CountObjects returned " + children);
            }
            boolean changed = false;
            for (int i = 0; i < children; i++) {
                MemorySegment child = PageEditBindings.formObject(obj, i);
                if (child == null || child.equals(MemorySegment.NULL)) continue;
                if (optimizeTree(rawDoc, rawPage, child, maxImageDpi, jpegQuality,
                        maxImageEdgePx, convertPngToJpeg, jpegProgressive, allowQualityOnly,
                        depth + 1)) {
                    changed = true;
                }
            }
            if (changed) {
                int ok = (int) PageEditBindings.FPDFFormObj_MarkDirty.invokeExact(obj);
                if (ok == 0) throw new JPDFiumException("FPDFFormObj_MarkDirty failed");
            }
            return changed;
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    /** Image-object metadata read before any pixels are decoded. */
    private record ImageMeta(int width, int height, int bpp, int colorspace, float dpi) {}

    // Quality-only re-encoding (no resize) below this encoded size saves too little
    // to pay for a decode + encode cycle and generation loss.
    private static final long MIN_REENCODE_BYTES = 8L * 1024;

    // Returns true when the image object was rewritten.
    private static boolean optimizeImage(MemorySegment rawDoc, MemorySegment rawPage,
                                         MemorySegment imgObj, int maxImageDpi, int jpegQuality,
                                         int maxImageEdgePx, boolean convertPngToJpeg,
                                         boolean jpegProgressive, boolean allowQualityOnly) {
        ImageMeta meta = readMeta(imgObj, rawPage);
        if (meta == null || meta.width() <= 0 || meta.height() <= 0) return false;

        double scale = 1.0;
        if (maxImageDpi > 0 && meta.dpi() > 0f && meta.dpi() > maxImageDpi) {
            scale = Math.min(scale, (double) maxImageDpi / meta.dpi());
        }
        if (maxImageEdgePx > 0) {
            int longestEdge = Math.max(meta.width(), meta.height());
            if (longestEdge > maxImageEdgePx) {
                scale = Math.min(scale, (double) maxImageEdgePx / longestEdge);
            }
        }
        int targetW = Math.max(1, (int) Math.round(meta.width() * scale));
        int targetH = Math.max(1, (int) Math.round(meta.height() * scale));
        boolean resize = targetW < meta.width() || targetH < meta.height();

        boolean jpegOriginal = isJpegOriginal(imgObj);
        long rawLength = rawLength(imgObj);
        boolean wantJpeg = jpegQuality > 0 && (jpegOriginal || convertPngToJpeg);
        // Below the resize threshold a JPEG is only re-encoded when it is large enough
        // for the saving to matter; resized images always go through the encoder.
        boolean reencode = wantJpeg
                && (resize || (allowQualityOnly && rawLength >= MIN_REENCODE_BYTES));
        if (!resize && !reencode) return false;

        // Skip anything that cannot round-trip safely through a PDFium bitmap:
        // 1-bit scans go blurry, and CMYK/ICC/Indexed/Separation/Lab would lose
        // their colour space. Only 8-bit DeviceGray/DeviceRGB are re-embedded.
        if (!roundTrippable(meta)) return false;
        if (hasTransparency(imgObj)) return false;

        ImageSource src = readSource(rawDoc, rawPage, imgObj, meta);
        if (src == null || src.width() <= 0 || src.height() <= 0) return false;

        BufferedImage result = resize ? scale(src.image(), targetW, targetH) : src.image();

        if (reencode) {
            byte[] jpeg = encodeJpeg(result, jpegQuality, jpegProgressive);
            if (jpeg != null && adoptJpeg(jpeg.length, rawLength, resize)
                    && embedJpegInline(imgObj, jpeg)) {
                return true;
            }
            // Fall through to the bitmap path only when the pixels actually shrank.
        }
        return resize && bitmapLikelySmaller(result, rawLength) && embedBitmap(imgObj, result);
    }

    // The bitmap fallback writes uncompressed/Flate pixels. Estimate its encoded
    // size before replacing the stream so a failed JPEG encode cannot inflate the PDF.
    private static boolean bitmapLikelySmaller(BufferedImage image, long rawLength) {
        if (rawLength <= 0) return false;
        byte[] pixels = image.getType() == BufferedImage.TYPE_BYTE_GRAY
                ? grayBytes(image) : rgbBytes(image);
        if (pixels == null || pixels.length == 0) return false;
        Deflater deflater = new Deflater(6);
        try {
            deflater.setInput(pixels);
            deflater.finish();
            byte[] buffer = new byte[8192];
            long total = 0;
            while (!deflater.finished()) {
                total += deflater.deflate(buffer);
                if (total >= rawLength) return false;
            }
            return total < rawLength;
        } finally {
            deflater.end();
        }
    }

    // A resize is adopted whenever the JPEG is smaller than the original stream: the
    // pixel count itself already dropped. Without a resize the encoder must save at
    // least 5% (and 512 bytes) to justify re-encoding an existing JPEG.
    private static boolean adoptJpeg(long newLength, long rawLength, boolean resized) {
        if (newLength <= 0) return false;
        if (rawLength <= 0) return resized;
        if (resized) return newLength < rawLength;
        return newLength + 512 < rawLength && newLength * 100 < rawLength * 95;
    }

    // Encoded length of the image's current stream, or -1 when PDFium cannot report it.
    private static long rawLength(MemorySegment imgObj) {
        return ImageObjBindings.imageDataRawLength(imgObj);
    }

    // Reads width/height/bpp/colorspace/dpi without decoding pixels, so an
    // oversized or unsupported image is rejected before any native decode.
    private static ImageMeta readMeta(MemorySegment imgObj, MemorySegment rawPage) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment meta = arena.allocate(META_BYTES);
            int ok = (int) ImageObjBindings.FPDFImageObj_GetImageMetadata.invokeExact(
                    imgObj, rawPage, meta);
            if (ok == 0) return null;
            int width = meta.get(JAVA_INT, 0);
            int height = meta.get(JAVA_INT, 4);
            float hdpi = meta.get(JAVA_FLOAT, 8);
            float vdpi = meta.get(JAVA_FLOAT, 12);
            int bpp = meta.get(JAVA_INT, 16);
            int cs = meta.get(JAVA_INT, 20);
            // Use the larger axis: an image with one axis above the threshold
            // must still be downsampled even if the other axis is below it.
            float dpi = Math.max(hdpi, vdpi);
            return new ImageMeta(width, height, bpp, cs, dpi > 0f ? dpi : -1f);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    // True only for 8-bit DeviceGray/DeviceRGB within the decoded-pixel cap.
    private static boolean roundTrippable(ImageMeta m) {
        if (m.width() <= 0 || m.height() <= 0) return false;
        if ((long) m.width() * m.height() > MAX_PIXELS) return false;
        return switch (m.colorspace()) {
            case CS_DEVICE_GRAY -> m.bpp() == 8;
            case CS_DEVICE_RGB -> m.bpp() == 24;
            default -> false;
        };
    }

    // FPDFImageObj_HasTransparency covers SMask/alpha. Re-encoding such an image
    // as an opaque JPEG would make formerly transparent pixels visible.
    private static boolean hasTransparency(MemorySegment imgObj) {
        if (ImageObjBindings.FPDFImageObj_HasTransparency == null) return true;
        try {
            return (int) ImageObjBindings.FPDFImageObj_HasTransparency.invokeExact(imgObj) == 1;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return true;
        }
    }

    // Native-resolution pixels, in preference order: PDFium's own bitmap, the
    // decoded colour data, then a page rasterization as a last resort.
    private static ImageSource readSource(MemorySegment rawDoc, MemorySegment rawPage,
                                          MemorySegment imgObj, ImageMeta meta) {
        MemorySegment own = MemorySegment.NULL;
        try {
            own = (MemorySegment) ImageObjBindings.FPDFImageObj_GetBitmap.invokeExact(imgObj);
            if (!own.equals(MemorySegment.NULL)) {
                ImageSource s = bitmapToSource(own);
                if (s != null) return s;
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
        } finally {
            // FPDFImageObj_GetBitmap hands ownership to the caller; leaking it
            // leaks the decoded image for every image object that is optimized.
            if (!own.equals(MemorySegment.NULL)) {
                try {
                    PageEditBindings.FPDFBitmap_Destroy.invokeExact(own);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                }
            }
        }

        ImageSource decoded = decodedSource(imgObj, meta);
        if (decoded != null) return decoded;

        // The rendered fallback applies the image matrix, so feeding those pixels
        // back into the original object would transform it twice. Skip the rewrite.
        return null;
    }

    private static ImageSource decodedSource(MemorySegment imgObj, ImageMeta meta) {
        int width = meta.width();
        int height = meta.height();
        int channels = switch (meta.colorspace()) {
            case CS_DEVICE_GRAY -> 1;
            case CS_DEVICE_RGB -> 3;
            case CS_DEVICE_CMYK -> 4;
            default -> 0;
        };
        if (channels <= 0 || (long) width * height > MAX_PIXELS) return null;
        int pixels = width * height;
        try (Arena arena = Arena.ofConfined()) {
            long expected = (long) pixels * channels;
            long size = (long) ImageObjBindings.FPDFImageObj_GetImageDataDecoded
                    .invokeExact(imgObj, MemorySegment.NULL, 0L);
            if (size < expected) return null;

            MemorySegment buf = arena.allocate(size);
            long written = (long) ImageObjBindings.FPDFImageObj_GetImageDataDecoded
                    .invokeExact(imgObj, buf, size);
            if (written < expected) return null;

            // Grayscale stays grayscale: one byte per pixel, copied straight into
            // the raster instead of being expanded to 3-channel RGB.
            if (channels == 1) {
                BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
                if (!(gray.getRaster().getDataBuffer() instanceof DataBufferByte dbb)) return null;
                byte[] px = dbb.getData();
                int rowStride = ((ComponentSampleModel) gray.getRaster().getSampleModel())
                        .getScanlineStride();
                MemorySegment dst = MemorySegment.ofArray(px);
                for (int y = 0; y < height; y++) {
                    MemorySegment.copy(buf, (long) y * width, dst, (long) y * rowStride, width);
                }
                return new ImageSource(gray, width, height);
            }

            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            if (!(img.getRaster().getDataBuffer() instanceof DataBufferInt dbi)
                    || dbi.getData().length < pixels) {
                return null;
            }
            int[] rgba = dbi.getData();
            int format = channels == 3 ? RUST_FMT_RGB24 : RUST_FMT_CMYK32;
            // Native kernel reads the decode buffer and writes straight into the raster.
            if (!nativeUnpack(buf, width, height, (long) width * channels, format, rgba, pixels)) {
                byte[] data = buf.asSlice(0, (long) pixels * channels).toArray(JAVA_BYTE);
                unpackPixels(data, pixels, channels, rgba);
            }
            return new ImageSource(img, width, height);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    // Native path: writes into `dst` with no extra Java copy. Returns false when the
    // symbol is absent, the format is unsupported, or the native call reports failure.
    static boolean nativeUnpack(MemorySegment src, int width, int height, long srcStride,
                                int format, int[] dst, int pixels) {
        if (format == 0 || !NATIVE_UNPACK) return false;
        int bpp = switch (format) {
            case 1 -> 1;
            case 2, 4 -> 3;
            case 3, 5 -> 4;
            default -> 0;
        };
        if (bpp == 0 || width <= 0 || height <= 0) return false;
        // Reject strides whose row-major extent overflows or exceeds the source
        // buffer, so the native kernel can never read past the mapped bytes.
        long required;
        try {
            required = Math.addExact(
                    Math.multiplyExact(srcStride, height - 1L), (long) width * bpp);
        } catch (ArithmeticException e) {
            return false;
        }
        if (srcStride < (long) width * bpp
                || src.byteSize() < required
                || (long) pixels != (long) width * height) {
            return false;
        }
        try {
            MemorySegment out = MemorySegment.ofArray(dst).asSlice(0, (long) pixels * Integer.BYTES);
            int rc = JpdfiumH.jpdfium_rust_unpack_pixels(src, width, height, srcStride, format, out, pixels);
            return rc == 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    // Unpack gray8/rgb24/cmyk32 into opaque ARGB ints straight into `dst`; branch-free
    // loops so the JIT can vectorise. Returns false when channels is not one of 1, 3, 4.
    static boolean unpackPixels(byte[] data, int pixels, int channels, int[] dst) {
        switch (channels) {
            case 1 -> {
                for (int i = 0; i < pixels; i++) {
                    int v = data[i] & 0xFF;
                    dst[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
            }
            case 3 -> {
                for (int i = 0, o = 0; i < pixels; i++, o += 3) {
                    dst[i] = 0xFF000000 | ((data[o] & 0xFF) << 16)
                            | ((data[o + 1] & 0xFF) << 8) | (data[o + 2] & 0xFF);
                }
            }
            case 4 -> {
                for (int i = 0, o = 0; i < pixels; i++, o += 4) {
                    int k = data[o + 3] & 0xFF;
                    int c = 255 - Math.min(255, (data[o] & 0xFF) + k);
                    int m = 255 - Math.min(255, (data[o + 1] & 0xFF) + k);
                    int y = 255 - Math.min(255, (data[o + 2] & 0xFF) + k);
                    dst[i] = 0xFF000000 | (c << 16) | (m << 8) | y;
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static ImageSource bitmapToSource(MemorySegment bitmap) throws Throwable {
        int width = (int) PageEditBindings.FPDFBitmap_GetWidth.invokeExact(bitmap);
        int height = (int) PageEditBindings.FPDFBitmap_GetHeight.invokeExact(bitmap);
        if (width <= 0 || height <= 0) return null;

        int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
        int fmt = (int) PageEditBindings.FPDFBitmap_GetFormat.invokeExact(bitmap);
        MemorySegment buf = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
        if (buf.equals(MemorySegment.NULL)) return null;

        // Validate first: a zero-length buffer or bad stride would make the row copies overflow.
        int bytesPerPixel = fmt == FPDF_BITMAP_GRAY ? 1
                : fmt == FPDF_BITMAP_BGR ? 3
                : (fmt == FPDF_BITMAP_BGRx || fmt == FPDF_BITMAP_BGRA) ? 4 : 0;
        if (bytesPerPixel == 0 || stride <= 0 || stride < (long) width * bytesPerPixel
                || (long) stride * height > Integer.MAX_VALUE
                || (long) width * height > MAX_PIXELS) {
            return null;
        }
        int pixels = width * height;
        buf = buf.reinterpret((long) stride * height);

        // BGRx/BGRA bytes already form the little-endian ARGB int, so copy rows
        // directly. BGRx's pad byte is unused and is often 0, so read it as
        // TYPE_INT_RGB (opaque) rather than letting a zero pad mean transparent.
        if (fmt == FPDF_BITMAP_BGRx || fmt == FPDF_BITMAP_BGRA) {
            int type = fmt == FPDF_BITMAP_BGRA
                    ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage img = new BufferedImage(width, height, type);
            int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int rowStride = ((SinglePixelPackedSampleModel) img.getRaster().getSampleModel()).getScanlineStride();
            MemorySegment dst = MemorySegment.ofArray(px);
            for (int y = 0; y < height; y++) {
                MemorySegment.copy(buf, (long) y * stride, dst, (long) y * rowStride * 4, (long) width * 4);
            }
            return new ImageSource(img, width, height);
        }

        // Grayscale: 1 byte per pixel, copied per row into TYPE_BYTE_GRAY.
        if (fmt == FPDF_BITMAP_GRAY) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            int rowStride = ((ComponentSampleModel) img.getRaster().getSampleModel()).getScanlineStride();
            MemorySegment dst = MemorySegment.ofArray(px);
            for (int y = 0; y < height; y++) {
                MemorySegment.copy(buf, (long) y * stride, dst, (long) y * rowStride, width);
            }
            return new ImageSource(img, width, height);
        }

        // 3-byte packed BGR: the only format left, so unpack each pixel.
        byte[] raw = buf.toArray(JAVA_BYTE);
        int[] px = new int[pixels];
        int idx = 0;
        for (int y = 0; y < height; y++) {
            int o = y * stride;
            for (int x = 0; x < width; x++) {
                int b = raw[o] & 0xFF;
                int g = raw[o + 1] & 0xFF;
                int r = raw[o + 2] & 0xFF;
                px[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                idx++;
                o += 3;
            }
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] raster = img.getRaster().getDataBuffer() instanceof DataBufferInt dbi
                ? dbi.getData() : null;
        if (raster != null && raster.length >= px.length) {
            System.arraycopy(px, 0, raster, 0, px.length);
        } else {
            img.setRGB(0, 0, width, height, px, 0, width);
        }
        return new ImageSource(img, width, height);
    }

    private static BufferedImage scale(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) return src;
        BufferedImage nativeScaled = scaleWithRust(src, width, height);
        if (nativeScaled != null) return nativeScaled;
        // Halve repeatedly before the final draw: a single bilinear step from a
        // 3000 px scan to 1000 px aliases detail that Ghostscript's averaging
        // downsampler keeps. Progressive reduction costs a couple of copies and
        // only runs for images that are being rewritten anyway.
        BufferedImage current = src;
        int w = src.getWidth();
        int h = src.getHeight();
        while (w > width * 2 && h > height * 2) {
            w = Math.max(width, w / 2);
            h = Math.max(height, h / 2);
            current = drawScaled(current, w, h);
        }
        return drawScaled(current, width, height);
    }

    /**
     * Lanczos3 resampling through the Rust SIMD kernel. It keeps substantially
     * more high-frequency detail than the Java bilinear fallback at the same
     * target size, so the resulting JPEG survives a lower edge cap.
     */
    private static BufferedImage scaleWithRust(BufferedImage src, int width, int height) {
        if (!RustBridgeBindings.isSupported()) return null;
        boolean gray = src.getType() == BufferedImage.TYPE_BYTE_GRAY;
        byte[] pixels = gray ? grayBytes(src) : rgbBytes(src);
        if (pixels == null) return null;
        byte[] scaled = RustBridgeBindings.rustResizePixels(
                pixels, src.getWidth(), src.getHeight(), gray ? 1 : 3, width, height);
        if (scaled == null || scaled.length < width * height * (gray ? 1 : 3)) return null;
        return gray ? grayImage(scaled, width, height) : rgbImage(scaled, width, height);
    }

    private static byte[] grayBytes(BufferedImage img) {
        if (img.getType() != BufferedImage.TYPE_BYTE_GRAY
                || !(img.getRaster().getDataBuffer() instanceof DataBufferByte dbb)) {
            return null;
        }
        int width = img.getWidth();
        int height = img.getHeight();
        int rowStride = ((ComponentSampleModel) img.getRaster().getSampleModel())
                .getScanlineStride();
        byte[] bytes = dbb.getData();
        if (rowStride == width) return bytes;
        byte[] tight = new byte[width * height];
        for (int y = 0; y < height; y++) {
            System.arraycopy(bytes, y * rowStride, tight, y * width, width);
        }
        return tight;
    }

    private static byte[] rgbBytes(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        byte[] out = new byte[width * height * 3];
        if (img.getType() == BufferedImage.TYPE_INT_RGB
                && img.getRaster().getDataBuffer() instanceof DataBufferInt dbi
                && img.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sp) {
            int[] px = dbi.getData();
            int rowStride = sp.getScanlineStride();
            int o = 0;
            for (int y = 0; y < height; y++) {
                int i = y * rowStride;
                for (int x = 0; x < width; x++, i++, o += 3) {
                    int p = px[i];
                    out[o] = (byte) (p >> 16);
                    out[o + 1] = (byte) (p >> 8);
                    out[o + 2] = (byte) p;
                }
            }
            return out;
        }
        int[] px = img.getRGB(0, 0, width, height, null, 0, width);
        for (int i = 0, o = 0; i < px.length; i++, o += 3) {
            int p = px[i];
            out[o] = (byte) (p >> 16);
            out[o + 1] = (byte) (p >> 8);
            out[o + 2] = (byte) p;
        }
        return out;
    }

    private static BufferedImage rgbImage(byte[] pixels, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int i = 0, o = 0; i < px.length; i++, o += 3) {
            px[i] = 0xFF000000
                    | ((pixels[o] & 0xFF) << 16)
                    | ((pixels[o + 1] & 0xFF) << 8)
                    | (pixels[o + 2] & 0xFF);
        }
        return img;
    }

    private static BufferedImage grayImage(byte[] pixels, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(pixels, 0, dst, 0, Math.min(dst.length, pixels.length));
        return img;
    }

    private static BufferedImage drawScaled(BufferedImage src, int width, int height) {
        int type = src.getType();
        if (type == BufferedImage.TYPE_CUSTOM) {
            type = BufferedImage.TYPE_INT_RGB;
        }
        BufferedImage out = new BufferedImage(width, height, type);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static boolean embedBitmap(MemorySegment imgObj,
                                       BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return embedGrayBitmap(imgObj, img);
        }
        int width = img.getWidth();
        int height = img.getHeight();

        MemorySegment bitmap = createBitmap(width, height, FPDF_BITMAP_BGRx);
        if (bitmap.equals(MemorySegment.NULL)) return false;

        try {
            int fmt = (int) PageEditBindings.FPDFBitmap_GetFormat.invokeExact(bitmap);
            int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
            // Mirror the bitmapToSource guard: a zero-length buffer or bad stride would overflow.
            MemorySegment bufferSeg =
                    (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
            int bpp = fmt == FPDF_BITMAP_BGR ? 3
                    : (fmt == FPDF_BITMAP_BGRx || fmt == FPDF_BITMAP_BGRA) ? 4 : 0;
            if (bufferSeg.equals(MemorySegment.NULL) || bpp == 0 || stride < (long) width * bpp
                    || (long) stride * height > Integer.MAX_VALUE
                    || (long) width * height > MAX_PIXELS) {
                return false;
            }
            int pixels = width * height;
            MemorySegment buf = bufferSeg.reinterpret((long) stride * height);

            // Fast path: an int-backed raster whose stride equals the width — ARGB is byte-identical
            // to little-endian BGRx, so rows copy directly.
            if (bpp == 4 && img.getType() != BufferedImage.TYPE_CUSTOM
                    && img.getRaster().getDataBuffer() instanceof DataBufferInt dbi
                    && img.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sp
                    && sp.getScanlineStride() == width
                    && dbi.getData().length >= pixels) {
                int[] px = dbi.getData();
                int n = pixels;
                // Opaque pad byte, as before.
                for (int i = 0; i < n; i++) px[i] |= 0xFF000000;
                MemorySegment src = MemorySegment.ofArray(px);
                for (int y = 0; y < height; y++) {
                    MemorySegment.copy(src, (long) y * width * 4, buf, (long) y * stride, (long) width * 4);
                }
                int ok = (int) PageEditBindings.FPDFImageObj_SetBitmap.invokeExact(
                        MemorySegment.NULL, 0, imgObj, bitmap);
                return ok != 0;
            }

            // Fallback: per-pixel unpack (3-byte BGR output or non-int raster).
            int[] px;
            if (img.getType() != BufferedImage.TYPE_CUSTOM
                    && img.getRaster().getDataBuffer() instanceof DataBufferInt dbi
                    && dbi.getData().length >= pixels) {
                px = dbi.getData();
            } else {
                px = img.getRGB(0, 0, width, height, null, 0, width);
            }
            byte[] raw = new byte[stride * height];
            int idx = 0;
            for (int y = 0; y < height; y++) {
                int o = y * stride;
                for (int x = 0; x < width; x++) {
                    int argb = px[idx];
                    idx++;
                    raw[o] = (byte) (argb & 0xFF);
                    raw[o + 1] = (byte) ((argb >> 8) & 0xFF);
                    raw[o + 2] = (byte) ((argb >> 16) & 0xFF);
                    if (bpp == 4) raw[o + 3] = (byte) 0xFF;
                    o += bpp;
                }
            }
            MemorySegment.copy(MemorySegment.ofArray(raw), 0, buf, 0, raw.length);

            int ok = (int) PageEditBindings.FPDFImageObj_SetBitmap.invokeExact(
                    MemorySegment.NULL, 0, imgObj, bitmap);
            return ok != 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        } finally {
            try {
                PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
        }
    }

    // Re-embeds an 8-bit grayscale image as FPDF_BITMAP_GRAY so it stays gray
    // instead of expanding to 3-channel RGB.
    private static boolean embedGrayBitmap(MemorySegment imgObj, BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        if (!(img.getRaster().getDataBuffer() instanceof DataBufferByte dbb)) return false;
        MemorySegment bitmap = createBitmap(width, height, FPDF_BITMAP_GRAY);
        if (bitmap.equals(MemorySegment.NULL)) return false;
        try {
            int fmt = (int) PageEditBindings.FPDFBitmap_GetFormat.invokeExact(bitmap);
            int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
            MemorySegment bufferSeg =
                    (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
            if (fmt != FPDF_BITMAP_GRAY || bufferSeg.equals(MemorySegment.NULL)
                    || stride < width || (long) stride * height > Integer.MAX_VALUE
                    || (long) width * height > MAX_PIXELS) {
                return false;
            }
            byte[] px = dbb.getData();
            int rowStride = ((ComponentSampleModel) img.getRaster().getSampleModel())
                    .getScanlineStride();
            MemorySegment buf = bufferSeg.reinterpret((long) stride * height);
            MemorySegment src = MemorySegment.ofArray(px);
            for (int y = 0; y < height; y++) {
                MemorySegment.copy(src, (long) y * rowStride, buf, (long) y * stride, width);
            }
            int ok = (int) PageEditBindings.FPDFImageObj_SetBitmap.invokeExact(
                    MemorySegment.NULL, 0, imgObj, bitmap);
            return ok != 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        } finally {
            try {
                PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
        }
    }

    // True when the image object's stream is JPEG (DCTDecode), so it can be re-encoded as JPEG.
    private static boolean isJpegOriginal(MemorySegment imgObj) {
        if (ImageObjBindings.FPDFImageObj_GetImageFilterCount == null
                || ImageObjBindings.FPDFImageObj_GetImageFilter == null) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            int filters =
                    (int) ImageObjBindings.FPDFImageObj_GetImageFilterCount.invokeExact(imgObj);
            for (int i = 0; i < filters; i++) {
                MemorySegment buf = arena.allocate(16);
                long n = (long) ImageObjBindings.FPDFImageObj_GetImageFilter.invokeExact(
                        imgObj, i, buf, 16L);
                if (n > 0 && "DCTDecode".equals(buf.getString(0))) return true;
            }
            return false;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    private static byte[] encodeJpeg(BufferedImage img, int quality, boolean progressive) {
        int q = Math.min(100, Math.max(1, quality));
        // The Rust encoder uses optimized Huffman tables, trellis quantization and
        // deringing; its coefficients can differ from ImageIO at the same quality.
        boolean gray = img.getType() == BufferedImage.TYPE_BYTE_GRAY;
        byte[] pixels = gray ? grayBytes(img) : rgbBytes(img);
        byte[] best = null;
        if (pixels != null) {
            best = RustBridgeBindings.rustEncodeJpeg(
                    pixels, img.getWidth(), img.getHeight(), gray ? 1 : 3, q, progressive);
        }
        // Optional libvips codec (jpdfium-vips on the classpath) writes JPEG
        // with its own optimized/trellis tables. Keep whichever stream is
        // smaller; both are valid baseline JPEG for PDFium.
        if (!progressive && ImageCodecs.hasCodec()) {
            try {
                byte[] vips = ImageCodecs.encode(img, ImageFormat.JPEG, q);
                if (vips != null && (best == null || vips.length < best.length)) {
                    best = vips;
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep the Rust/ImageIO result.
            }
        }
        if (best != null) return best;
        if (progressive) return null;
        try {
            return ImageCodecs.encode(img, ImageFormat.JPEG, q);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Replaces the object's content with {@code jpeg} via {@code FPDFImageObj_LoadJpegFileInline}.
     * PDFium copies the data during the call, so the file access need only outlive it.
     */
    private static boolean embedJpegInline(MemorySegment imgObj, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return false;
        try (Arena arena = Arena.ofConfined()) {
            JpegFileAccess access = new JpegFileAccess(jpeg);
            MemorySegment stub = Linker.nativeLinker().upcallStub(
                    GET_BLOCK.bindTo(access), GET_BLOCK_DESCRIPTOR, arena);
            // FPDF_FILEACCESS: { unsigned long m_FileLen; GetBlock* m_GetBlock; void* m_Param; }
            // `unsigned long` is 4 bytes on Windows and 8 elsewhere, while the
            // pointer slots stay at offsets 8 and 16 either way.
            MemorySegment fileAccess = arena.allocate(24);
            if (LONG_CARRIER) {
                fileAccess.set(JAVA_LONG, 0, (long) jpeg.length);
            } else {
                fileAccess.set(JAVA_INT, 0, jpeg.length);
            }
            fileAccess.set(ADDRESS, 8, stub);
            fileAccess.set(ADDRESS, 16, MemorySegment.NULL);
            int ok = (int) PageEditBindings.FPDFImageObj_LoadJpegFileInline.invokeExact(
                    MemorySegment.NULL, 0, imgObj, fileAccess);
            return ok != 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    /** Backs the {@code FPDF_FILEACCESS.m_GetBlock} callback used by the inline JPEG load. */
    private static final class JpegFileAccess {
        private final byte[] data;

        JpegFileAccess(byte[] data) {
            this.data = data;
        }

        int getBlock(MemorySegment param, long position, MemorySegment pBuf, long size) {
            if (position < 0 || size < 0 || position > data.length
                    || size > data.length - position) return 0;
            MemorySegment.copy(
                    data, (int) position, pBuf.reinterpret(size), JAVA_BYTE, 0, (int) size);
            return 1;
        }

        /** The 32-bit-{@code long} counterpart PDFium calls on Windows. */
        int getBlockInt(MemorySegment param, int position, MemorySegment pBuf, int size) {
            return getBlock(
                    param, Integer.toUnsignedLong(position), pBuf, Integer.toUnsignedLong(size));
        }
    }

    private static MemorySegment createBitmap(int width, int height, int format) {
        try {
            return (MemorySegment) PageEditBindings.FPDFBitmap_CreateEx.invokeExact(
                    width, height, format, MemorySegment.NULL, 0);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return MemorySegment.NULL;
        }
    }
}
