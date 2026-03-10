package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;

import java.lang.foreign.MemorySegment;
import java.util.*;

/**
 * Comprehensive document statistics and analytics.
 *
 * <p>Aggregates data from text extraction, image analysis, page objects,
 * bookmarks, annotations, and metadata into a single report.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("report.pdf"))) {
 *     DocumentStats stats = PdfAnalytics.analyze(doc);
 *     System.out.println(stats.summary());
 *     System.out.println(stats.toJson());
 * }
 * }</pre>
 */
public final class PdfAnalytics {

    private static final int AVG_READING_SPEED_WPM = 250;

    private PdfAnalytics() {}

    /**
     * Analyze the document and return comprehensive statistics.
     *
     * @param doc the document to analyze
     * @return document statistics
     */
    public static DocumentStats analyze(PdfDocument doc) {
        return analyze(doc, -1);
    }

    /**
     * Analyze the document with known file size.
     *
     * @param doc      the document to analyze
     * @param fileSize file size in bytes, or -1 if unknown
     * @return document statistics
     */
    public static DocumentStats analyze(PdfDocument doc, long fileSize) {
        MemorySegment rawDoc = doc.rawHandle();
        int pageCount = doc.pageCount();

        int totalWords = 0;
        int totalChars = 0;
        int totalLines = 0;
        int totalImages = 0;
        long totalImageBytes = 0;
        int totalTextObjects = 0;
        int totalPathObjects = 0;
        List<PageSize> pageSizes = new ArrayList<>();
        Map<String, Integer> fontUsage = new TreeMap<>();
        Map<String, Integer> imageFormats = new TreeMap<>();

        for (int i = 0; i < pageCount; i++) {
            try (PdfPage page = doc.page(i)) {
                pageSizes.add(page.size());

                // Text stats
                try {
                    PageText pt = PdfTextExtractor.extractPage(doc, i);
                    totalWords += pt.wordCount();
                    totalChars += pt.charCount();
                    totalLines += pt.lineCount();

                    // Font usage from text characters
                    for (var ch : pt.chars()) {
                        if (ch.fontName() != null && !ch.fontName().isBlank()) {
                            fontUsage.merge(ch.fontName(), 1, Integer::sum);
                        }
                    }
                } catch (Exception ignored) {}

                // Page object stats
                MemorySegment rawPage = page.rawHandle();
                PageContentSummary pcs = PdfPageObjects.summarize(rawPage);
                totalTextObjects += pcs.textObjectCount();
                totalPathObjects += pcs.pathObjectCount();

                // Image stats
                ImageStats imgStats = PdfImageExtractor.stats(rawDoc, rawPage);
                totalImages += imgStats.totalImages();
                totalImageBytes += imgStats.totalRawBytes();
                imgStats.formatBreakdown().forEach(
                        (fmt, cnt) -> imageFormats.merge(fmt, cnt, Integer::sum));
            }
        }

        // Bookmarks
        List<Bookmark> bookmarks = PdfBookmarks.list(rawDoc);
        int bookmarkCount = countBookmarks(bookmarks);

        // Annotations
        int totalAnnotations = 0;
        Map<AnnotationType, Integer> annotCounts = new EnumMap<>(AnnotationType.class);
        for (int i = 0; i < pageCount; i++) {
            try (PdfPage page = doc.page(i)) {
                for (Annotation a : PdfAnnotations.list(page.rawHandle())) {
                    annotCounts.merge(a.type(), 1, Integer::sum);
                    totalAnnotations++;
                }
            }
        }

        // Attachments
        int attachmentCount = PdfAttachments.count(rawDoc);

        // Signatures
        int signatureCount = PdfSignatures.count(rawDoc);

        // Reading time
        double readingTimeMinutes = totalWords > 0 ? (double) totalWords / AVG_READING_SPEED_WPM : 0;

        // Font list (simplified to just name→char count)
        List<FontUsage> fontList = fontUsage.entrySet().stream()
                .map(e -> new FontUsage(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(FontUsage::charCount).reversed())
                .toList();

        return new DocumentStats(
                pageCount, totalWords, totalChars, totalLines,
                readingTimeMinutes,
                totalImages, totalImageBytes, imageFormats,
                totalTextObjects, totalPathObjects,
                bookmarkCount, totalAnnotations, annotCounts,
                attachmentCount, signatureCount,
                pageSizes, fontList, fileSize
        );
    }

    private static int countBookmarks(List<Bookmark> bookmarks) {
        int count = 0;
        for (Bookmark bm : bookmarks) {
            count++;
            if (bm.hasChildren()) count += countBookmarks(bm.children());
        }
        return count;
    }

    /**
     * Font usage statistics.
     */
    public record FontUsage(String fontName, int charCount) {}

    /**
     * Comprehensive document statistics.
     */
    public record DocumentStats(
            int pageCount,
            int totalWords,
            int totalChars,
            int totalLines,
            double estimatedReadingTimeMinutes,
            int totalImages,
            long totalImageBytes,
            Map<String, Integer> imageFormatBreakdown,
            int textObjectCount,
            int pathObjectCount,
            int bookmarkCount,
            int annotationCount,
            Map<AnnotationType, Integer> annotationBreakdown,
            int attachmentCount,
            int signatureCount,
            List<PageSize> pageSizes,
            List<FontUsage> fonts,
            long fileSize
    ) {
        /** Human-readable summary. */
        public String summary() {
            StringBuilder sb = new StringBuilder(256);
            sb.append(String.format("Pages: %d", pageCount));
            sb.append(String.format(", Words: %,d", totalWords));
            sb.append(String.format(", Chars: %,d", totalChars));
            if (estimatedReadingTimeMinutes > 0) {
                sb.append(String.format(", Est. reading time: %.1f min", estimatedReadingTimeMinutes));
            }
            if (totalImages > 0) {
                sb.append(String.format(", Images: %d (%s raw)",
                        totalImages, humanReadableSize(totalImageBytes)));
            }
            if (bookmarkCount > 0) sb.append(String.format(", Bookmarks: %d", bookmarkCount));
            if (annotationCount > 0) sb.append(String.format(", Annotations: %d", annotationCount));
            if (attachmentCount > 0) sb.append(String.format(", Attachments: %d", attachmentCount));
            if (signatureCount > 0) sb.append(String.format(", Signatures: %d", signatureCount));
            if (!fonts.isEmpty()) sb.append(String.format(", Fonts: %d", fonts.size()));
            if (fileSize > 0) sb.append(String.format(", File size: %s", humanReadableSize(fileSize)));
            return sb.toString();
        }

        /** Machine-readable JSON. */
        public String toJson() {
            StringBuilder sb = new StringBuilder("{");
            sb.append(String.format("\"pageCount\":%d", pageCount));
            sb.append(String.format(",\"totalWords\":%d", totalWords));
            sb.append(String.format(",\"totalChars\":%d", totalChars));
            sb.append(String.format(",\"totalLines\":%d", totalLines));
            sb.append(String.format(",\"estimatedReadingTimeMinutes\":%.1f", estimatedReadingTimeMinutes));
            sb.append(String.format(",\"totalImages\":%d", totalImages));
            sb.append(String.format(",\"totalImageBytes\":%d", totalImageBytes));
            sb.append(String.format(",\"textObjectCount\":%d", textObjectCount));
            sb.append(String.format(",\"pathObjectCount\":%d", pathObjectCount));
            sb.append(String.format(",\"bookmarkCount\":%d", bookmarkCount));
            sb.append(String.format(",\"annotationCount\":%d", annotationCount));
            sb.append(String.format(",\"attachmentCount\":%d", attachmentCount));
            sb.append(String.format(",\"signatureCount\":%d", signatureCount));

            sb.append(",\"fonts\":[");
            for (int i = 0; i < fonts.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(String.format("{\"name\":\"%s\",\"charCount\":%d}",
                        escapeJson(fonts.get(i).fontName()), fonts.get(i).charCount()));
            }
            sb.append(']');

            sb.append(String.format(",\"fileSize\":%d", fileSize));
            sb.append('}');
            return sb.toString();
        }

        private static String humanReadableSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }

        private static String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
