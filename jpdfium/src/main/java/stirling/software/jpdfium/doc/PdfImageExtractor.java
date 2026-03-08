package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.ColorSpaceType;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.ImageObjBindings;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extract images from PDF pages via page object enumeration.
 */
public final class PdfImageExtractor {

    private PdfImageExtractor() {}

    /**
     * Extract all images from a page.
     *
     * @param rawDoc  raw FPDF_DOCUMENT
     * @param rawPage raw FPDF_PAGE
     * @param pageIndex 0-based page index
     * @return extracted images with metadata and pixel data
     */
    public static List<ExtractedImage> extract(MemorySegment rawDoc, MemorySegment rawPage, int pageIndex) {
        int objCount;
        try {
            objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) { return Collections.emptyList(); }

        List<ExtractedImage> images = new ArrayList<>();
        int imgIndex = 0;

        for (int i = 0; i < objCount; i++) {
            MemorySegment obj;
            try {
                obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
            } catch (Throwable t) { continue; }
            if (obj.equals(MemorySegment.NULL)) continue;

            int type;
            try {
                type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
            } catch (Throwable t) { continue; }
            if (type != 3) continue; // 3 = IMAGE

            ExtractedImage img = extractOne(rawDoc, rawPage, obj, imgIndex, pageIndex, i);
            if (img != null) {
                images.add(img);
                imgIndex++;
            }
        }
        return Collections.unmodifiableList(images);
    }

    /**
     * Get image statistics for a page without extracting pixel data.
     */
    public static ImageStats stats(MemorySegment rawDoc, MemorySegment rawPage) {
        int objCount;
        try {
            objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
        } catch (Throwable t) { return new ImageStats(0, 0, Map.of()); }

        int total = 0;
        long totalRawBytes = 0;
        Map<String, Integer> formats = new HashMap<>();

        for (int i = 0; i < objCount; i++) {
            MemorySegment obj;
            try {
                obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
            } catch (Throwable t) { continue; }
            if (obj.equals(MemorySegment.NULL)) continue;

            int type;
            try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
            catch (Throwable t) { continue; }
            if (type != 3) continue;

            total++;
            String filter = getFirstFilter(obj);
            formats.merge(filter, 1, Integer::sum);

            try {
                long rawSize = (long) ImageObjBindings.FPDFImageObj_GetImageDataRaw.invokeExact(
                        obj, MemorySegment.NULL, 0L);
                totalRawBytes += rawSize;
            } catch (Throwable ignored) {}
        }

        return new ImageStats(total, totalRawBytes, Collections.unmodifiableMap(formats));
    }

    private static ExtractedImage extractOne(MemorySegment rawDoc, MemorySegment rawPage,
                                              MemorySegment obj, int imgIndex, int pageIndex, int objIndex) {
        try (Arena arena = Arena.ofConfined()) {
            int width = 0, height = 0, bpp = 0;
            ColorSpaceType colorSpace = ColorSpaceType.UNKNOWN;
            byte[] decodedBytes = null;
            byte[] rawBytes = null;

            // Primary method: use FPDFImageObj_GetRenderedBitmap for reliable pixel extraction
            try {
                MemorySegment bmp = (MemorySegment) ImageObjBindings.FPDFImageObj_GetRenderedBitmap
                        .invokeExact(rawDoc, rawPage, obj);
                if (!bmp.equals(MemorySegment.NULL)) {
                    try {
                        int bmpW = (int) PageEditBindings.FPDFBitmap_GetWidth.invokeExact(bmp);
                        int bmpH = (int) PageEditBindings.FPDFBitmap_GetHeight.invokeExact(bmp);
                        int stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bmp);
                        if (bmpW > 0 && bmpH > 0 && stride > 0) {
                            MemorySegment buf = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bmp);
                            decodedBytes = buf.reinterpret((long) stride * bmpH)
                                    .toArray(ValueLayout.JAVA_BYTE);
                            width = bmpW;
                            height = bmpH;
                            bpp = 32; // RGBA bitmap is always 32 bpp
                            colorSpace = ColorSpaceType.DEVICE_RGB;
                        }
                    } finally {
                        PageEditBindings.FPDFBitmap_Destroy.invokeExact(bmp);
                    }
                }
            } catch (Throwable ignored) {}

            // If rendered bitmap failed, try metadata + raw data
            if (width <= 0 || height <= 0 || decodedBytes == null) {
                MemorySegment metaSeg = arena.allocate(ImageObjBindings.IMAGE_METADATA_LAYOUT);
                int metaOk;
                try {
                    metaOk = (int) ImageObjBindings.FPDFImageObj_GetImageMetadata.invokeExact(obj, rawPage, metaSeg);
                } catch (Throwable t) { metaOk = 0; }

                if (metaOk != 0) {
                    width = metaSeg.get(ValueLayout.JAVA_INT, 0);
                    height = metaSeg.get(ValueLayout.JAVA_INT, 4);
                    bpp = metaSeg.get(ValueLayout.JAVA_INT, 16);
                    int cs = metaSeg.get(ValueLayout.JAVA_INT, 20);
                    colorSpace = ColorSpaceType.fromCode(cs);
                }

                // Bounds
                Rect bounds = getObjBounds(obj);

                // Filter name
                String filter = getFirstFilter(obj);

                // Raw data
                try {
                    long rawSize = (long) ImageObjBindings.FPDFImageObj_GetImageDataRaw.invokeExact(
                            obj, MemorySegment.NULL, 0L);
                    if (rawSize > 0) {
                        MemorySegment rawBuf = arena.allocate(rawSize);
                        ImageObjBindings.FPDFImageObj_GetImageDataRaw.invokeExact(obj, rawBuf, rawSize);
                        rawBytes = rawBuf.toArray(ValueLayout.JAVA_BYTE);
                    }
                } catch (Throwable ignored) {}

                // Decoded data (pixels)
                try {
                    long decSize = (long) ImageObjBindings.FPDFImageObj_GetImageDataDecoded.invokeExact(
                            obj, MemorySegment.NULL, 0L);
                    if (decSize > 0) {
                        MemorySegment decBuf = arena.allocate(decSize);
                        ImageObjBindings.FPDFImageObj_GetImageDataDecoded.invokeExact(obj, decBuf, decSize);
                        decodedBytes = decBuf.toArray(ValueLayout.JAVA_BYTE);
                    }
                } catch (Throwable ignored) {}
            }

            // Bounds (if not already retrieved)
            Rect bounds = getObjBounds(obj);

            // Filter name (if not already retrieved)
            String filter = getFirstFilter(obj);

            // Skip images with no valid dimensions
            if (width <= 0 || height <= 0) {
                return null;
            }

            return new ExtractedImage(imgIndex, pageIndex, objIndex, width, height, bpp,
                    colorSpace, filter, bounds, rawBytes, decodedBytes);
        } catch (Throwable t) { return null; }
    }

    private static Rect getObjBounds(MemorySegment obj) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment l = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment r = arena.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment t = arena.allocate(ValueLayout.JAVA_FLOAT);
            int ok = (int) PageEditBindings.FPDFPageObj_GetBounds.invokeExact(obj, l, b, r, t);
            if (ok == 0) return new Rect(0, 0, 0, 0);
            float left = l.get(ValueLayout.JAVA_FLOAT, 0);
            float bottom = b.get(ValueLayout.JAVA_FLOAT, 0);
            float right = r.get(ValueLayout.JAVA_FLOAT, 0);
            float top = t.get(ValueLayout.JAVA_FLOAT, 0);
            return new Rect(left, bottom, right - left, top - bottom);
        } catch (Throwable e) { return new Rect(0, 0, 0, 0); }
    }

    private static String getFirstFilter(MemorySegment obj) {
        try {
            int filterCount = (int) ImageObjBindings.FPDFImageObj_GetImageFilterCount.invokeExact(obj);
            if (filterCount <= 0) return "none";
            try (Arena arena = Arena.ofConfined()) {
                long needed = (long) ImageObjBindings.FPDFImageObj_GetImageFilter.invokeExact(
                        obj, 0, MemorySegment.NULL, 0L);
                if (needed <= 1) return "none";
                MemorySegment buf = arena.allocate(needed);
                ImageObjBindings.FPDFImageObj_GetImageFilter.invokeExact(obj, 0, buf, needed);
                return FfmHelper.fromByteString(buf, needed);
            }
        } catch (Throwable t) { return "unknown"; }
    }
}
