package stirling.software.jpdfium.text;

import java.util.Collections;
import java.util.List;

/**
 * A table detected on a PDF page.
 *
 * <p>Tables are detected by geometric clustering of text positions.
 * Each cell contains the text found within that cell boundary.
 *
 * @param rows     list of rows, each row is a list of cell strings
 * @param x        left edge of table bounding box (PDF points)
 * @param y        bottom edge of table bounding box (PDF points)
 * @param width    table width (PDF points)
 * @param height   table height (PDF points)
 */
public record Table(
        List<List<String>> rows,
        float x,
        float y,
        float width,
        float height
) {
    public Table {
        rows = rows.stream()
                .map(Collections::unmodifiableList)
                .toList();
    }

    /** Number of data rows. */
    public int rowCount() { return rows.size(); }

    /** Number of columns (based on first row). */
    public int colCount() { return rows.isEmpty() ? 0 : rows.get(0).size(); }

    /** Returns the table as a 2D String array [row][col]. */
    public String[][] asGrid() {
        if (rows.isEmpty()) return new String[0][0];
        int cols = colCount();
        String[][] grid = new String[rows.size()][cols];
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < cols; c++) {
                grid[r][c] = c < row.size() ? row.get(c) : "";
            }
        }
        return grid;
    }

    /** Export the table as CSV. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(',');
                String cell = row.get(c);
                if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
                    sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(cell);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Export the table as a JSON array of arrays. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) sb.append(',');
            sb.append('[');
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(',');
                sb.append('"').append(escapeJson(row.get(c))).append('"');
            }
            sb.append(']');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
