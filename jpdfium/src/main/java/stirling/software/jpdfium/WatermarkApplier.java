package stirling.software.jpdfium;

import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Position;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import java.util.Set;

/**
 * Apply watermarks (text or image) to PDF documents.
 *
 * <pre>{@code
 * Watermark wm = Watermark.text("CONFIDENTIAL")
 *     .font(FontName.HELVETICA).size(72).color(0x40FF0000)
 *     .rotation(45).opacity(0.25f)
 *     .position(Position.CENTER)
 *     .build();
 *
 * WatermarkApplier.apply(doc, wm);                    // all pages
 * WatermarkApplier.apply(doc, wm, Set.of(0, 1, 2));   // specific pages
 * }</pre>
 */
public final class WatermarkApplier {

    private WatermarkApplier() {}

    /**
     * Apply a watermark to all pages in the document.
     *
     * @param doc       target document
     * @param watermark watermark configuration
     */
    public static void apply(PdfDocument doc, Watermark watermark) {
        for (int i = 0; i < doc.pageCount(); i++) {
            applyToPage(doc, i, watermark);
        }
    }

    /**
     * Apply a watermark to specific pages.
     *
     * @param doc         target document
     * @param watermark   watermark configuration
     * @param pageIndices zero-based page indices
     */
    public static void apply(PdfDocument doc, Watermark watermark, Set<Integer> pageIndices) {
        for (int idx : pageIndices) {
            if (idx >= 0 && idx < doc.pageCount()) {
                applyToPage(doc, idx, watermark);
            }
        }
    }

    private static void applyToPage(PdfDocument doc, int pageIndex, Watermark watermark) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawDoc = doc.rawHandle();
            MemorySegment rawPage = page.rawHandle();
            PageSize size = page.size();

            switch (watermark.type()) {
                case TEXT -> applyTextWatermark(rawDoc, rawPage, size, watermark);
                case IMAGE -> applyImageWatermark(rawDoc, rawPage, size, watermark);
            }
        }
    }

    private static void applyTextWatermark(MemorySegment rawDoc, MemorySegment rawPage,
                                            PageSize pageSize, Watermark wm) {
        MemorySegment textObj = PdfPageEditor.createTextObject(rawDoc, wm.fontName().fontName(), wm.fontSize());
        PdfPageEditor.setText(textObj, wm.text());

        int a = (wm.argbColor() >> 24) & 0xFF;
        int r = (wm.argbColor() >> 16) & 0xFF;
        int g = (wm.argbColor() >> 8) & 0xFF;
        int b = wm.argbColor() & 0xFF;
        PdfPageEditor.setFillColor(textObj, r, g, b, a);

        float pageW = pageSize.width();
        float pageH = pageSize.height();

        float textWidth = wm.text().length() * wm.fontSize() * 0.5f;
        float textHeight = wm.fontSize();

        float[] pos = computePosition(wm.position(), pageW, pageH,
                textWidth, textHeight, wm.margin());

        float radians = (float) Math.toRadians(wm.rotation());
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        float cx = pageW / 2f;
        float cy = pageH / 2f;

        if (wm.rotation() != 0 && wm.position() == Position.CENTER) {
            float tx = cx - (textWidth * cos - textHeight * sin) / 2f;
            float ty = cy - (textWidth * sin + textHeight * cos) / 2f;
            PdfPageEditor.transform(textObj, cos, sin, -sin, cos, tx, ty);
        } else {
            if (wm.rotation() != 0) {
                PdfPageEditor.transform(textObj, cos, sin, -sin, cos, pos[0], pos[1]);
            } else {
                PdfPageEditor.transform(textObj, 1, 0, 0, 1, pos[0], pos[1]);
            }
        }

        PdfPageEditor.insertObject(rawPage, textObj);
        PdfPageEditor.generateContent(rawPage);
    }

    private static void applyImageWatermark(MemorySegment rawDoc, MemorySegment rawPage,
                                             PageSize pageSize, Watermark wm) {
        BufferedImage img = wm.image();
        if (img == null) return;

        BufferedImage withAlpha = applyOpacity(img, wm.opacity());

        int w = withAlpha.getWidth();
        int h = withAlpha.getHeight();

        float targetW = pageSize.width() * 0.3f;
        float scale = targetW / w;
        float targetH = h * scale;

        float[] pos = computePosition(wm.position(), pageSize.width(), pageSize.height(),
                targetW, targetH, wm.margin());

        MemorySegment imgObj = PdfPageEditor.createImageObject(rawDoc);

        PdfPageEditor.transform(imgObj, targetW, 0, 0, targetH, pos[0], pos[1]);

        PdfPageEditor.insertObject(rawPage, imgObj);
        PdfPageEditor.generateContent(rawPage);
    }

    private static BufferedImage applyOpacity(BufferedImage src, float opacity) {
        BufferedImage result = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    /**
     * Compute the [x, y] position for the watermark based on position enum.
     */
    private static float[] computePosition(Position pos, float pageW, float pageH,
                                            float objW, float objH, float margin) {
        float x, y;
        switch (pos) {
            case TOP_LEFT -> { x = margin; y = pageH - objH - margin; }
            case TOP_CENTER -> { x = (pageW - objW) / 2f; y = pageH - objH - margin; }
            case TOP_RIGHT -> { x = pageW - objW - margin; y = pageH - objH - margin; }
            case MIDDLE_LEFT -> { x = margin; y = (pageH - objH) / 2f; }
            case MIDDLE_RIGHT -> { x = pageW - objW - margin; y = (pageH - objH) / 2f; }
            case BOTTOM_LEFT -> { x = margin; y = margin; }
            case BOTTOM_CENTER -> { x = (pageW - objW) / 2f; y = margin; }
            case BOTTOM_RIGHT -> { x = pageW - objW - margin; y = margin; }
            default -> { x = (pageW - objW) / 2f; y = (pageH - objH) / 2f; }
        }
        return new float[]{ x, y };
    }
}
