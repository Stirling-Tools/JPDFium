package stirling.software.jpdfium;

import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Position;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
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
                                            PageSize pageSize, Watermark watermark) {
        MemorySegment textObject = PdfPageEditor.createTextObject(
                rawDoc, watermark.fontName().fontName(), watermark.fontSize());
        PdfPageEditor.setText(textObject, watermark.text());

        int argb = watermark.argbColor();
        int alpha = (argb >> 24) & 0xFF;
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        PdfPageEditor.setFillColor(textObject, red, green, blue, alpha);

        float pageWidth = pageSize.width();
        float pageHeight = pageSize.height();

        float textWidth = watermark.text().length() * watermark.fontSize() * 0.5f;
        float textHeight = watermark.fontSize();

        float[] positionCoordinates = computePosition(watermark.position(), pageWidth, pageHeight,
                textWidth, textHeight, watermark.margin());

        float radians = (float) Math.toRadians(watermark.rotation());
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        float centerX = pageWidth / 2f;
        float centerY = pageHeight / 2f;

        if (watermark.rotation() != 0 && watermark.position() == Position.CENTER) {
            float tx = centerX - (textWidth * cos - textHeight * sin) / 2f;
            float ty = centerY - (textWidth * sin + textHeight * cos) / 2f;
            PdfPageEditor.transform(textObject, cos, sin, -sin, cos, tx, ty);
        } else {
            if (watermark.rotation() != 0) {
                PdfPageEditor.transform(textObject, cos, sin, -sin, cos, positionCoordinates[0], positionCoordinates[1]);
            } else {
                PdfPageEditor.transform(textObject, 1, 0, 0, 1, positionCoordinates[0], positionCoordinates[1]);
            }
        }

        PdfPageEditor.insertObject(rawPage, textObject);
        PdfPageEditor.generateContent(rawPage);
    }

    private static void applyImageWatermark(MemorySegment rawDoc, MemorySegment rawPage,
                                             PageSize pageSize, Watermark watermark) {
        BufferedImage image = watermark.image();
        if (image == null) return;

        BufferedImage imageWithAlpha = applyOpacity(image, watermark.opacity());

        int width = imageWithAlpha.getWidth();
        int height = imageWithAlpha.getHeight();

        float targetWidth = pageSize.width() * watermark.scale();
        float scale = targetWidth / width;
        float targetHeight = height * scale;

        float[] positionCoordinates = computePosition(watermark.position(), pageSize.width(), pageSize.height(),
                targetWidth, targetHeight, watermark.margin());

        MemorySegment imageObject = PdfPageEditor.createImageObject(rawDoc);

        PdfPageEditor.transform(imageObject, targetWidth, 0, 0, targetHeight, positionCoordinates[0], positionCoordinates[1]);

        PdfPageEditor.insertObject(rawPage, imageObject);
        PdfPageEditor.generateContent(rawPage);
    }

    private static BufferedImage applyOpacity(BufferedImage sourceImage, float opacity) {
        BufferedImage resultImage = new BufferedImage(
                sourceImage.getWidth(), sourceImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resultImage.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            graphics.drawImage(sourceImage, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return resultImage;
    }

    /**
     * Compute the [x, y] position for the watermark based on position enum.
     */
    private static float[] computePosition(Position pos, float pageW, float pageH,
                                            float objW, float objH, float margin) {
        float x;
        float y;
        switch (pos) {
            case TOP_LEFT -> {
                x = margin;
                y = pageH - objH - margin;
            }
            case TOP_CENTER -> {
                x = (pageW - objW) / 2f;
                y = pageH - objH - margin;
            }
            case TOP_RIGHT -> {
                x = pageW - objW - margin;
                y = pageH - objH - margin;
            }
            case MIDDLE_LEFT -> {
                x = margin;
                y = (pageH - objH) / 2f;
            }
            case MIDDLE_RIGHT -> {
                x = pageW - objW - margin;
                y = (pageH - objH) / 2f;
            }
            case BOTTOM_LEFT -> {
                x = margin;
                y = margin;
            }
            case BOTTOM_CENTER -> {
                x = (pageW - objW) / 2f;
                y = margin;
            }
            case BOTTOM_RIGHT -> {
                x = pageW - objW - margin;
                y = margin;
            }
            default -> {
                x = (pageW - objW) / 2f;
                y = (pageH - objH) / 2f;
            }
        }
        return new float[]{ x, y };
    }
}
