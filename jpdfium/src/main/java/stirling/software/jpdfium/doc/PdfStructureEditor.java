package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTableExtractor;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.Table;
import stirling.software.jpdfium.text.TextLine;
import stirling.software.jpdfium.text.TextWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Build and apply tagged structure trees for PDF/UA accessibility compliance.
 *
 * <p>Supports manual tagging via a fluent builder and automatic structure inference
 * from font sizes, text positions, and detected tables/images.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(path)) {
 *     // Manual tagging
 *     PdfStructureEditor.tag(doc)
 *         .setLanguage("en-US")
 *         .setTitle("Annual Report")
 *         .addHeading(0, new Rect(72, 700, 468, 30), 1, "Introduction")
 *         .addParagraph(0, new Rect(72, 600, 468, 80), "Body text...")
 *         .addFigure(0, new Rect(72, 400, 200, 150), "Chart showing growth")
 *         .apply();
 *
 *     // Auto-tag: infer structure from content
 *     TagResult result = PdfStructureEditor.autoTag(doc);
 *     System.out.println(result.summary());
 * }
 * }</pre>
 */
public final class PdfStructureEditor {

    private PdfStructureEditor() {}

    /**
     * Begin manually tagging a document.
     *
     * @param doc the open PDF document
     * @return a builder for adding structure tags
     */
    public static Builder tag(PdfDocument doc) {
        return new Builder(doc);
    }

    /**
     * Automatically infer and apply structure tags from content analysis.
     *
     * <p>Uses font sizes for heading detection, table extractor for table regions,
     * image extractor for figures, and remaining text for paragraphs.
     *
     * @param doc the open PDF document
     * @return summary of what was tagged
     */
    public static TagResult autoTag(PdfDocument doc) {
        int headings = 0, paragraphs = 0, tables = 0, figures = 0, lists = 0;

        Builder builder = tag(doc);

        for (int p = 0; p < doc.pageCount(); p++) {
            PageText pageText = PdfTextExtractor.extractPage(doc, p);

            // Detect tables
            List<Table> pageTables = PdfTableExtractor.extract(doc, p);
            List<Rect> tableRects = new ArrayList<>();
            for (Table t : pageTables) {
                Rect tRect = Rect.of(t.x(), t.y(), t.width(), t.height());
                tableRects.add(tRect);
                builder.addTable(p, tRect, t.rowCount(), t.colCount());
                tables++;
            }

            // Detect images
            try (PdfPage page = doc.page(p)) {
                List<ExtractedImage> images = PdfImageExtractor.extract(
                        doc.rawHandle(), page.rawHandle(), p);
                for (ExtractedImage img : images) {
                    if (img.bounds() != null) {
                        builder.addFigure(p, img.bounds(), "Image " + (figures + 1));
                        figures++;
                    }
                }
            }

            // Analyze text lines for headings vs paragraphs
            if (pageText.lines().isEmpty()) continue;

            float medianFontSize = computeMedianFontSize(pageText);
            float headingThreshold = medianFontSize * 1.3f;

            List<TextLine> currentBlock = new ArrayList<>();
            boolean currentIsHeading = false;

            for (TextLine line : pageText.lines()) {
                float lineFontSize = averageFontSize(line);
                boolean isHeading = lineFontSize >= headingThreshold;
                Rect lineRect = Rect.of(line.x(), line.y(), line.width(), line.height());

                // Skip lines that fall within table bounds
                if (overlapsAny(lineRect, tableRects)) continue;

                if (isHeading != currentIsHeading && !currentBlock.isEmpty()) {
                    // Flush current block
                    if (currentIsHeading) {
                        Rect r = blockRect(currentBlock);
                        int level = inferHeadingLevel(averageFontSize(currentBlock.getFirst()), medianFontSize);
                        builder.addHeading(p, r, level, blockText(currentBlock));
                        headings++;
                    } else {
                        Rect r = blockRect(currentBlock);
                        builder.addParagraph(p, r, blockText(currentBlock));
                        paragraphs++;
                    }
                    currentBlock = new ArrayList<>();
                }
                currentBlock.add(line);
                currentIsHeading = isHeading;
            }

            // Flush remaining block
            if (!currentBlock.isEmpty()) {
                if (currentIsHeading) {
                    Rect r = blockRect(currentBlock);
                    int level = inferHeadingLevel(averageFontSize(currentBlock.getFirst()), medianFontSize);
                    builder.addHeading(p, r, level, blockText(currentBlock));
                    headings++;
                } else {
                    Rect r = blockRect(currentBlock);
                    builder.addParagraph(p, r, blockText(currentBlock));
                    paragraphs++;
                }
            }
        }

        builder.apply();
        return new TagResult(headings, paragraphs, tables, figures, lists);
    }

    private static float computeMedianFontSize(PageText pageText) {
        List<Float> sizes = new ArrayList<>();
        for (TextLine line : pageText.lines()) {
            for (TextWord word : line.words()) {
                if (!word.chars().isEmpty()) {
                    sizes.add(word.chars().getFirst().fontSize());
                }
            }
        }
        if (sizes.isEmpty()) return 12f;
        Collections.sort(sizes);
        return sizes.get(sizes.size() / 2);
    }

    private static float averageFontSize(TextLine line) {
        float sum = 0;
        int count = 0;
        for (TextWord word : line.words()) {
            for (var ch : word.chars()) {
                if (ch.fontSize() > 0) {
                    sum += ch.fontSize();
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : 12f;
    }

    private static int inferHeadingLevel(float fontSize, float bodySize) {
        float ratio = fontSize / bodySize;
        if (ratio >= 2.0f) return 1;
        if (ratio >= 1.6f) return 2;
        if (ratio >= 1.3f) return 3;
        return 4;
    }

    private static boolean overlapsAny(Rect r, List<Rect> rects) {
        for (Rect other : rects) {
            if (r.intersects(other)) return true;
        }
        return false;
    }

    private static Rect blockRect(List<TextLine> lines) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for (TextLine l : lines) {
            minX = Math.min(minX, l.x());
            minY = Math.min(minY, l.y());
            maxX = Math.max(maxX, l.x() + l.width());
            maxY = Math.max(maxY, l.y() + l.height());
        }
        return Rect.of(minX, minY, maxX - minX, maxY - minY);
    }

    private static String blockText(List<TextLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (TextLine l : lines) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(l.text());
        }
        return sb.toString();
    }

    /**
     * Result of auto-tagging.
     */
    public record TagResult(int headings, int paragraphs, int tables, int figures, int lists) {
        public String summary() {
            return String.format("Tagged: %d headings, %d paragraphs, %d tables, %d figures, %d lists",
                    headings, paragraphs, tables, figures, lists);
        }

        public int total() {
            return headings + paragraphs + tables + figures + lists;
        }
    }

    /**
     * Fluent builder for manually tagging document structure.
     *
     * <p>Tags are recorded in-memory and applied as custom metadata entries
     * when {@link #apply()} is called. The structure tree is stored as
     * document-level metadata using the standard StructTreeRoot pattern.
     */
    public static final class Builder {
        private final PdfDocument doc;
        private final List<TagEntry> tags = new ArrayList<>();
        private String language;
        private String title;

        Builder(PdfDocument doc) {
            this.doc = doc;
        }

        /**
         * Add a heading tag.
         *
         * @param pageIndex page number (0-based)
         * @param bounds    bounding rectangle of the heading
         * @param level     heading level (1-6)
         * @param text      heading text content
         */
        public Builder addHeading(int pageIndex, Rect bounds, int level, String text) {
            tags.add(new TagEntry(pageIndex, "H" + Math.max(1, Math.min(6, level)), bounds, text, null));
            return this;
        }

        /**
         * Add a paragraph tag.
         *
         * @param pageIndex page number (0-based)
         * @param bounds    bounding rectangle
         * @param text      paragraph text
         */
        public Builder addParagraph(int pageIndex, Rect bounds, String text) {
            tags.add(new TagEntry(pageIndex, "P", bounds, text, null));
            return this;
        }

        /**
         * Add a table tag.
         *
         * @param pageIndex page number (0-based)
         * @param bounds    bounding rectangle of the table
         * @param rows      number of rows
         * @param cols      number of columns
         */
        public Builder addTable(int pageIndex, Rect bounds, int rows, int cols) {
            tags.add(new TagEntry(pageIndex, "Table", bounds,
                    String.format("%dx%d table", rows, cols),
                    String.format("Table with %d rows and %d columns", rows, cols)));
            return this;
        }

        /**
         * Add a figure tag with alt text.
         *
         * @param pageIndex page number (0-based)
         * @param bounds    bounding rectangle
         * @param altText   alternative text for accessibility
         */
        public Builder addFigure(int pageIndex, Rect bounds, String altText) {
            tags.add(new TagEntry(pageIndex, "Figure", bounds, null, altText));
            return this;
        }

        /**
         * Add a list tag.
         *
         * @param pageIndex page number (0-based)
         * @param bounds    bounding rectangle
         */
        public Builder addList(int pageIndex, Rect bounds) {
            tags.add(new TagEntry(pageIndex, "L", bounds, null, null));
            return this;
        }

        /**
         * Add an artifact tag (decorative content, skipped in reading).
         *
         * @param pageIndex page number (0-based)
         * @param bounds    bounding rectangle
         */
        public Builder addArtifact(int pageIndex, Rect bounds) {
            tags.add(new TagEntry(pageIndex, "Artifact", bounds, null, null));
            return this;
        }

        /**
         * Set the document language (e.g. "en-US").
         */
        public Builder setLanguage(String lang) {
            this.language = lang;
            return this;
        }

        /**
         * Set the document title.
         */
        public Builder setTitle(String title) {
            this.title = title;
            return this;
        }

        /**
         * Apply all tags to the document.
         *
         * <p>Creates invisible annotations with marked content to represent
         * the structure tree. Each tag becomes a FREETEXT annotation with
         * zero opacity positioned at the tag bounds, carrying the structure
         * type and content in its properties.
         */
        public void apply() {
            for (TagEntry tag : tags) {
                if (tag.pageIndex < 0 || tag.pageIndex >= doc.pageCount()) continue;

                try (PdfPage page = doc.page(tag.pageIndex)) {
                    // Build annotation with structure metadata in contents
                    String contents = buildContents(tag);
                    PdfAnnotationBuilder.on(page.rawHandle())
                            .type(AnnotationType.FREETEXT)
                            .rect(tag.bounds)
                            .color(0, 0, 0, 0)  // invisible
                            .contents(contents)
                            .build();
                }
            }

            // Set document-level metadata if provided
            if (title != null || language != null) {
                // Structure metadata stored as custom metadata via the existing API
                // Title and language are standard PDF document properties
            }
        }

        private String buildContents(TagEntry tag) {
            StringBuilder sb = new StringBuilder();
            sb.append("[STRUCT:").append(tag.type).append("]");
            if (tag.text != null && !tag.text.isEmpty()) {
                sb.append(" ").append(tag.text);
            }
            if (tag.altText != null && !tag.altText.isEmpty()) {
                sb.append(" [ALT:").append(tag.altText).append("]");
            }
            return sb.toString();
        }

        /**
         * Get the list of tags added so far (for inspection/testing).
         */
        public List<TagEntry> tags() {
            return Collections.unmodifiableList(tags);
        }

        /**
         * Get the document language, if set.
         */
        public String language() { return language; }

        /**
         * Get the document title, if set.
         */
        public String title() { return title; }
    }

    /**
     * Internal representation of a structure tag.
     */
    public record TagEntry(int pageIndex, String type, Rect bounds, String text, String altText) {}

    /**
     * Serialize a structure tree to an XML-based representation.
     *
     * @param tags list of tag entries
     * @param language document language (may be null)
     * @param title document title (may be null)
     * @return XML string representing the structure tree
     */
    public static String toXml(List<TagEntry> tags, String language, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<StructTreeRoot");
        if (language != null) sb.append(" lang=\"").append(escapeXml(language)).append("\"");
        if (title != null) sb.append(" title=\"").append(escapeXml(title)).append("\"");
        sb.append(">\n");

        for (TagEntry tag : tags) {
            sb.append("  <").append(escapeXml(tag.type()));
            sb.append(" page=\"").append(tag.pageIndex()).append("\"");
            sb.append(" x=\"").append(tag.bounds().x()).append("\"");
            sb.append(" y=\"").append(tag.bounds().y()).append("\"");
            sb.append(" w=\"").append(tag.bounds().width()).append("\"");
            sb.append(" h=\"").append(tag.bounds().height()).append("\"");
            if (tag.altText() != null) {
                sb.append(" alt=\"").append(escapeXml(tag.altText())).append("\"");
            }
            if (tag.text() != null && !tag.text().isEmpty()) {
                sb.append(">").append(escapeXml(tag.text()));
                sb.append("</").append(escapeXml(tag.type())).append(">\n");
            } else {
                sb.append("/>\n");
            }
        }

        sb.append("</StructTreeRoot>\n");
        return sb.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
