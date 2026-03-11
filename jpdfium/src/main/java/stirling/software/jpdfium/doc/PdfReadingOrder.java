package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.TextChar;
import stirling.software.jpdfium.text.TextLine;

import stirling.software.jpdfium.text.TextWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Detect reading order of text blocks on a page.
 *
 * <p>Analyzes spatial layout of text lines to classify them into regions
 * (header, body, sidebar, footnote) and determine a logical reading order.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("complex-layout.pdf"))) {
 *     var blocks = PdfReadingOrder.analyze(doc, 0);
 *     for (var block : blocks) {
 *         System.out.printf("[%s] (%.0f,%.0f): %s...%n",
 *             block.region(), block.y(), block.x(),
 *             block.text().substring(0, Math.min(60, block.text().length())));
 *     }
 * }
 * }</pre>
 */
public final class PdfReadingOrder {

    private PdfReadingOrder() {}

    /** Region classification based on position. */
    public enum Region {
        HEADER, BODY, SIDEBAR, FOOTNOTE, CAPTION
    }

    /** A text block with position, region classification, and reading order index. */
    public record TextBlock(int order, Region region, float x, float y, float width, float height, String text) {}

    /**
     * Analyze a single page and return text blocks in reading order.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return text blocks sorted by reading order
     */
    public static List<TextBlock> analyze(PdfDocument doc, int pageIndex) {
        PageText pageText = PdfTextExtractor.extractPage(doc, pageIndex);
        List<TextLine> lines = pageText.lines();
        if (lines.isEmpty()) return List.of();

        float pageHeight;
        float pageWidth;
        try (var page = doc.page(pageIndex)) {
            pageHeight = page.size().height();
            pageWidth = page.size().width();
        }

        // Group lines into blocks based on vertical proximity and x-alignment
        List<LineBlock> rawBlocks = groupIntoBlocks(lines, pageWidth);

        // Classify each block
        List<TextBlock> result = new ArrayList<>();
        for (LineBlock block : rawBlocks) {
            Region region = classifyRegion(block, pageWidth, pageHeight);
            String text = block.text();
            result.add(new TextBlock(0, region, block.minX, block.minY, 
                    block.maxX - block.minX, block.maxY - block.minY, text));
        }

        // Sort by reading order: top-to-bottom, left-to-right, headers first, footnotes last
        result.sort(Comparator
                .<TextBlock, Integer>comparing(b -> regionPriority(b.region()))
                .thenComparing(b -> -b.y())  // higher y = higher on page in PDF coords
                .thenComparing(TextBlock::x));

        // Assign order indices
        List<TextBlock> ordered = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            TextBlock b = result.get(i);
            ordered.add(new TextBlock(i, b.region(), b.x(), b.y(), b.width(), b.height(), b.text()));
        }
        return Collections.unmodifiableList(ordered);
    }

    /** Analyze all pages. */
    public static List<List<TextBlock>> analyzeAll(PdfDocument doc) {
        List<List<TextBlock>> result = new ArrayList<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            result.add(analyze(doc, i));
        }
        return Collections.unmodifiableList(result);
    }

    private static int regionPriority(Region r) {
        return switch (r) {
            case HEADER -> 0;
            case BODY -> 1;
            case SIDEBAR -> 2;
            case CAPTION -> 3;
            case FOOTNOTE -> 4;
        };
    }

    private static Region classifyRegion(LineBlock block, float pageW, float pageH) {
        float centerY = (block.minY + block.maxY) / 2;
        float centerX = (block.minX + block.maxX) / 2;
        float blockWidth = block.maxX - block.minX;

        // Top 10% of the page
        if (centerY > pageH * 0.9f) return Region.HEADER;

        // Bottom 10% of the page
        if (centerY < pageH * 0.1f) return Region.FOOTNOTE;

        // Narrow block far to one side: sidebar
        if (blockWidth < pageW * 0.3f && (centerX < pageW * 0.2f || centerX > pageW * 0.8f)) {
            return Region.SIDEBAR;
        }

        // Small block under an image-like gap: caption
        if (block.lineCount <= 2 && blockWidth < pageW * 0.5f) {
            return Region.CAPTION;
        }

        return Region.BODY;
    }

    private static List<LineBlock> groupIntoBlocks(List<TextLine> lines, float pageWidth) {
        if (lines.isEmpty()) return List.of();

        // Sort lines by Y descending (top of page first in PDF coords)
        List<TextLine> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> {
            float ay = firstCharY(a);
            float by = firstCharY(b);
            return Float.compare(by, ay);
        });

        List<LineBlock> blocks = new ArrayList<>();
        LineBlock current = new LineBlock();

        for (TextLine line : sorted) {
            List<TextChar> allChars = lineChars(line);
            if (allChars.isEmpty()) continue;
            float lineY = allChars.getFirst().y();
            float lineX = allChars.getFirst().x();
            float lineMaxX = allChars.getLast().x() + allChars.getLast().width();
            float lineH = allChars.getFirst().height();

            if (current.isEmpty()) {
                current.add(line, lineX, lineY, lineMaxX, lineH);
            } else {
                float gap = current.lastY - lineY;
                boolean sameColumn = Math.abs(lineX - current.minX) < 30f || 
                                     Math.abs(lineMaxX - current.maxX) < 30f;
                if (gap < lineH * 2.0f && sameColumn) {
                    current.add(line, lineX, lineY, lineMaxX, lineH);
                } else {
                    blocks.add(current);
                    current = new LineBlock();
                    current.add(line, lineX, lineY, lineMaxX, lineH);
                }
            }
        }
        if (!current.isEmpty()) blocks.add(current);
        return blocks;
    }

    private static class LineBlock {
        final List<TextLine> lines = new ArrayList<>();
        float minX = Float.MAX_VALUE, maxX = 0, minY = Float.MAX_VALUE, maxY = 0;
        float lastY;
        int lineCount;

        boolean isEmpty() { return lines.isEmpty(); }

        void add(TextLine line, float x, float y, float maxX, float h) {
            lines.add(line);
            this.minX = Math.min(this.minX, x);
            this.maxX = Math.max(this.maxX, maxX);
            this.minY = Math.min(this.minY, y);
            this.maxY = Math.max(this.maxY, y + h);
            this.lastY = y;
            lineCount++;
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (TextLine line : lines) {
                sb.append(line.text());
                sb.append('\n');
            }
            return sb.toString().strip();
        }
    }

    private static List<TextChar> lineChars(TextLine line) {
        List<TextChar> chars = new ArrayList<>();
        for (TextWord w : line.words()) {
            chars.addAll(w.chars());
        }
        return chars;
    }

    private static float firstCharY(TextLine line) {
        for (TextWord w : line.words()) {
            if (!w.chars().isEmpty()) return w.chars().getFirst().y();
        }
        return 0;
    }
}
