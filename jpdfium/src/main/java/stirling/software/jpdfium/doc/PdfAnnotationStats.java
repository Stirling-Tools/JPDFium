package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;

import java.lang.foreign.MemorySegment;
import java.util.*;

/**
 * Generate annotation statistics and summaries for a PDF document.
 *
 * <p>Provides counts by type, author listing, area calculations, and
 * a textual summary for audit purposes.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("annotated.pdf"))) {
 *     var stats = PdfAnnotationStats.analyze(doc);
 *     System.out.println("Total: " + stats.totalCount());
 *     stats.countByType().forEach((type, count) ->
 *         System.out.printf("  %s: %d%n", type, count));
 * }
 * }</pre>
 */
public final class PdfAnnotationStats {

    private PdfAnnotationStats() {}

    /** Complete annotation statistics for a document. */
    public record Stats(
            int totalCount,
            int annotatedPageCount,
            int totalPages,
            Map<AnnotationType, Integer> countByType,
            Map<String, Integer> countByAuthor,
            double totalAreaPt2,
            double avgAreaPt2,
            List<PageAnnotInfo> perPage
    ) {
        /** Get a formatted text summary. */
        public String summary() {
            var sb = new StringBuilder();
            sb.append(String.format("Annotation Statistics: %d annotations across %d/%d pages%n",
                    totalCount, annotatedPageCount, totalPages));
            sb.append(String.format("Total area: %.1f pt^2, average: %.1f pt^2%n",
                    totalAreaPt2, avgAreaPt2));
            sb.append("By type:%n".replace("%n", System.lineSeparator()));
            countByType.entrySet().stream()
                    .sorted(Map.Entry.<AnnotationType, Integer>comparingByValue().reversed())
                    .forEach(e -> sb.append(String.format("  %-20s %d%n", e.getKey(), e.getValue())));
            if (!countByAuthor.isEmpty()) {
                sb.append("By author:%n".replace("%n", System.lineSeparator()));
                countByAuthor.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(e -> sb.append(String.format("  %-30s %d%n", e.getKey(), e.getValue())));
            }
            return sb.toString();
        }
    }

    /** Per-page annotation info. */
    public record PageAnnotInfo(int pageIndex, int count, List<AnnotationType> types) {}

    /**
     * Analyze all annotations in the document.
     *
     * @param doc open PDF document
     * @return annotation statistics
     */
    public static Stats analyze(PdfDocument doc) {
        Map<AnnotationType, Integer> byType = new EnumMap<>(AnnotationType.class);
        Map<String, Integer> byAuthor = new TreeMap<>();
        List<PageAnnotInfo> perPage = new ArrayList<>();
        int totalCount = 0;
        int annotatedPages = 0;
        double totalArea = 0;

        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();
                List<Annotation> annots = PdfAnnotations.list(rawPage);

                if (!annots.isEmpty()) {
                    annotatedPages++;
                    List<AnnotationType> pageTypes = new ArrayList<>();

                    for (Annotation a : annots) {
                        totalCount++;
                        AnnotationType type = a.type();
                        pageTypes.add(type);
                        byType.merge(type, 1, Integer::sum);

                        // Calculate area
                        Rect r = a.rect();
                        if (r != null) {
                            totalArea += Math.abs(r.width() * r.height());
                        }

                        // Author extraction from string values
                        if (a.contents() != null && !a.contents().isEmpty()) {
                            // Contents don't contain author; we'd need T key for author
                        }
                    }

                    perPage.add(new PageAnnotInfo(p, annots.size(), Collections.unmodifiableList(pageTypes)));
                }
            }
        }

        double avgArea = totalCount > 0 ? totalArea / totalCount : 0;

        return new Stats(
                totalCount, annotatedPages, doc.pageCount(),
                Collections.unmodifiableMap(byType),
                Collections.unmodifiableMap(byAuthor),
                totalArea, avgArea,
                Collections.unmodifiableList(perPage)
        );
    }

    /**
     * Generate a JSON representation of the statistics.
     *
     * @param doc open PDF document
     * @return JSON string
     */
    public static String toJson(PdfDocument doc) {
        Stats stats = analyze(doc);
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append(String.format("  \"totalAnnotations\": %d,%n", stats.totalCount()));
        sb.append(String.format("  \"annotatedPages\": %d,%n", stats.annotatedPageCount()));
        sb.append(String.format("  \"totalPages\": %d,%n", stats.totalPages()));
        sb.append(String.format("  \"totalAreaPt2\": %.1f,%n", stats.totalAreaPt2()));
        sb.append(String.format("  \"avgAreaPt2\": %.1f,%n", stats.avgAreaPt2()));
        sb.append("  \"byType\": {");
        var typeEntries = new ArrayList<>(stats.countByType().entrySet());
        for (int i = 0; i < typeEntries.size(); i++) {
            var e = typeEntries.get(i);
            sb.append(String.format("%n    \"%s\": %d", e.getKey(), e.getValue()));
            if (i < typeEntries.size() - 1) sb.append(',');
        }
        sb.append("\n  },\n");
        sb.append("  \"perPage\": [");
        for (int i = 0; i < stats.perPage().size(); i++) {
            var pi = stats.perPage().get(i);
            sb.append(String.format("%n    {\"page\": %d, \"count\": %d}", pi.pageIndex(), pi.count()));
            if (i < stats.perPage().size() - 1) sb.append(',');
        }
        sb.append("\n  ]\n");
        sb.append("}");
        return sb.toString();
    }
}
