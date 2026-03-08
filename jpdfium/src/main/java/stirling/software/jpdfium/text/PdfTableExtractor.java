package stirling.software.jpdfium.text;

import stirling.software.jpdfium.PdfDocument;

import java.util.*;

/**
 * Extracts tables from PDF pages using geometric clustering of text positions.
 *
 * <p>The algorithm works by:
 * <ol>
 *   <li>Extracting all words with bounding boxes from a page</li>
 *   <li>Detecting vertical column boundaries by clustering X positions</li>
 *   <li>Detecting horizontal row boundaries by clustering Y positions</li>
 *   <li>Assigning words to grid cells based on their position</li>
 * </ol>
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("report.pdf"))) {
 *     List<Table> tables = PdfTableExtractor.extract(doc, 0);
 *     for (Table t : tables) {
 *         System.out.printf("Table: %d rows x %d cols%n", t.rowCount(), t.colCount());
 *         System.out.println(t.toCsv());
 *     }
 * }
 * }</pre>
 */
public final class PdfTableExtractor {

    /** Minimum number of columns for a region to be classified as a table. */
    private static final int MIN_COLUMNS = 2;
    /** Minimum number of data rows for a region to be classified as a table. */
    private static final int MIN_ROWS = 2;
    /** Tolerance for grouping words into the same row (fraction of typical line height). */
    private static final float ROW_TOLERANCE = 0.4f;
    /** Tolerance for grouping words into the same column (fraction of typical char width). */
    private static final float COL_GAP_FACTOR = 2.5f;

    private PdfTableExtractor() {}

    /**
     * Extract tables from a specific page.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @return list of detected tables (may be empty)
     */
    public static List<Table> extract(PdfDocument doc, int pageIndex) {
        PageText pageText = PdfTextExtractor.extractPage(doc, pageIndex);
        return extractFromPageText(pageText);
    }

    /**
     * Extract tables from all pages.
     *
     * @param doc open PDF document
     * @return map of page index to list of tables on that page
     */
    public static Map<Integer, List<Table>> extractAll(PdfDocument doc) {
        Map<Integer, List<Table>> results = new LinkedHashMap<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            List<Table> tables = extract(doc, i);
            if (!tables.isEmpty()) {
                results.put(i, tables);
            }
        }
        return results;
    }

    /**
     * Extract tables from pre-extracted page text.
     */
    static List<Table> extractFromPageText(PageText pageText) {
        List<TextWord> allWords = pageText.allWords();
        if (allWords.size() < MIN_COLUMNS * MIN_ROWS) {
            return List.of();
        }

        // Find the typical line height for row clustering
        float typicalHeight = medianHeight(allWords);
        if (typicalHeight <= 0) return List.of();

        float rowTolerance = typicalHeight * ROW_TOLERANCE;

        // Group all words by Y-position into rows
        List<WordRow> wordRows = groupIntoRows(allWords, rowTolerance);
        if (wordRows.size() < MIN_ROWS) return List.of();

        // Find column boundaries by analyzing X-position gaps across all rows
        List<float[]> columns = detectColumns(wordRows);
        if (columns.size() < MIN_COLUMNS) return List.of();

        // Build the table grid
        List<Table> tables = buildTables(wordRows, columns);

        return tables;
    }

    private static float medianHeight(List<TextWord> words) {
        float[] heights = new float[words.size()];
        for (int i = 0; i < words.size(); i++) {
            heights[i] = words.get(i).height();
        }
        Arrays.sort(heights);
        return heights[heights.length / 2];
    }

    /**
     * Group words into rows by Y-position clustering.
     * Returns rows sorted top-to-bottom (descending Y in PDF coords).
     */
    private static List<WordRow> groupIntoRows(List<TextWord> words, float tolerance) {
        // Sort by Y descending (top of page first in PDF coordinates)
        List<TextWord> sorted = new ArrayList<>(words);
        sorted.sort((a, b) -> Float.compare(b.y(), a.y()));

        List<WordRow> rows = new ArrayList<>();
        List<TextWord> currentRow = new ArrayList<>();
        float currentY = sorted.get(0).y();

        for (TextWord w : sorted) {
            if (!currentRow.isEmpty() && Math.abs(w.y() - currentY) > tolerance) {
                rows.add(new WordRow(currentRow, currentY));
                currentRow = new ArrayList<>();
            }
            currentRow.add(w);
            currentY = w.y();
        }
        if (!currentRow.isEmpty()) {
            rows.add(new WordRow(currentRow, currentY));
        }

        return rows;
    }

    /**
     * Detect column boundaries by finding consistent X-position gaps.
     * Returns a list of [leftX, rightX] for each column.
     */
    private static List<float[]> detectColumns(List<WordRow> rows) {
        // Collect all word X-positions (left edges and right edges)
        List<Float> leftEdges = new ArrayList<>();
        for (WordRow row : rows) {
            for (TextWord w : row.words) {
                leftEdges.add(w.x());
            }
        }
        Collections.sort(leftEdges);

        if (leftEdges.isEmpty()) return List.of();

        // Cluster left edges to find column starts
        float typicalWidth = 0;
        int count = 0;
        for (WordRow row : rows) {
            for (TextWord w : row.words) {
                typicalWidth += w.width();
                count++;
            }
        }
        typicalWidth = count > 0 ? typicalWidth / count : 10;
        float colGap = typicalWidth * COL_GAP_FACTOR;

        // Find clusters in the left-edge positions
        List<Float> columnStarts = new ArrayList<>();
        float prevX = leftEdges.get(0);
        columnStarts.add(prevX);

        for (int i = 1; i < leftEdges.size(); i++) {
            float x = leftEdges.get(i);
            if (x - prevX > colGap) {
                columnStarts.add(x);
            }
            prevX = x;
        }

        // Deduplicate close column starts
        List<Float> dedupedStarts = new ArrayList<>();
        dedupedStarts.add(columnStarts.get(0));
        for (int i = 1; i < columnStarts.size(); i++) {
            float last = dedupedStarts.get(dedupedStarts.size() - 1);
            if (columnStarts.get(i) - last > colGap * 0.5f) {
                dedupedStarts.add(columnStarts.get(i));
            }
        }

        // Convert to column ranges
        List<float[]> columns = new ArrayList<>();
        for (int i = 0; i < dedupedStarts.size(); i++) {
            float start = dedupedStarts.get(i);
            float end = (i + 1 < dedupedStarts.size()) ?
                    dedupedStarts.get(i + 1) : Float.MAX_VALUE;
            columns.add(new float[]{start, end});
        }

        return columns;
    }

    /**
     * Build tables from rows and column structures.
     */
    private static List<Table> buildTables(List<WordRow> wordRows, List<float[]> columns) {
        int numCols = columns.size();
        List<List<String>> tableRows = new ArrayList<>();

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;

        for (WordRow row : wordRows) {
            String[] cells = new String[numCols];
            Arrays.fill(cells, "");

            for (TextWord w : row.words) {
                float wordCenter = w.x() + w.width() / 2f;
                int col = findColumn(columns, wordCenter);
                if (col >= 0 && col < numCols) {
                    if (!cells[col].isEmpty()) cells[col] += " ";
                    cells[col] += w.text();
                }
            }

            // Only include rows that have content in multiple columns
            int filledCols = 0;
            for (String cell : cells) {
                if (!cell.isEmpty()) filledCols++;
            }

            if (filledCols >= 2) {
                tableRows.add(Arrays.asList(cells));

                // Update bounds
                for (TextWord w : row.words) {
                    minX = Math.min(minX, w.x());
                    minY = Math.min(minY, w.y());
                    maxX = Math.max(maxX, w.x() + w.width());
                    maxY = Math.max(maxY, w.y() + w.height());
                }
            }
        }

        if (tableRows.size() < MIN_ROWS) {
            return List.of();
        }

        return List.of(new Table(tableRows, minX, minY, maxX - minX, maxY - minY));
    }

    private static int findColumn(List<float[]> columns, float x) {
        for (int i = 0; i < columns.size(); i++) {
            if (x >= columns.get(i)[0] && x < columns.get(i)[1]) {
                return i;
            }
        }
        // Default to nearest column
        float minDist = Float.MAX_VALUE;
        int best = 0;
        for (int i = 0; i < columns.size(); i++) {
            float mid = (columns.get(i)[0] + columns.get(i)[1]) / 2f;
            float dist = Math.abs(x - mid);
            if (dist < minDist) {
                minDist = dist;
                best = i;
            }
        }
        return best;
    }

    private record WordRow(List<TextWord> words, float y) {}
}
