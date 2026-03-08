package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.text.PdfTableExtractor;
import stirling.software.jpdfium.text.Table;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SAMPLE 24 - Table Extraction.
 *
 * <p>Demonstrates geometric table detection and extraction from PDF pages,
 * with CSV and JSON export.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S24_TableExtract {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S24_TableExtract  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S24_table-extract");

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S24_TableExtract", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                Map<Integer, List<Table>> allTables = PdfTableExtractor.extractAll(doc);

                if (allTables.isEmpty()) {
                    System.out.println("  No tables detected");
                    continue;
                }

                System.out.printf("  Found tables on %d page(s)%n", allTables.size());

                int tableNum = 0;
                for (Map.Entry<Integer, List<Table>> entry : allTables.entrySet()) {
                    int pageIdx = entry.getKey();
                    List<Table> tables = entry.getValue();

                    for (Table table : tables) {
                        tableNum++;
                        System.out.printf("  Page %d: %d rows x %d cols " +
                                        "(bounds: %.0f,%.0f %.0fx%.0f)%n",
                                pageIdx, table.rowCount(), table.colCount(),
                                table.x(), table.y(), table.width(), table.height());

                        // Export CSV
                        String stem = SampleBase.stem(input);
                        Path csvFile = outDir.resolve(stem + "-table-" + tableNum + ".csv");
                        Files.writeString(csvFile, table.toCsv());
                        produced.add(csvFile);
                        System.out.printf("    -> %s%n", csvFile.getFileName());

                        // Export JSON
                        Path jsonFile = outDir.resolve(stem + "-table-" + tableNum + ".json");
                        Files.writeString(jsonFile, table.toJson());
                        produced.add(jsonFile);
                        System.out.printf("    -> %s%n", jsonFile.getFileName());

                        // Print first few rows
                        String[][] grid = table.asGrid();
                        int previewRows = Math.min(3, grid.length);
                        for (int r = 0; r < previewRows; r++) {
                            StringBuilder sb = new StringBuilder("    | ");
                            for (int c = 0; c < grid[r].length; c++) {
                                String cell = grid[r][c];
                                if (cell.length() > 20) cell = cell.substring(0, 17) + "...";
                                sb.append(String.format("%-20s | ", cell));
                            }
                            System.out.println(sb);
                        }
                        if (grid.length > previewRows) {
                            System.out.printf("    ... (%d more rows)%n",
                                    grid.length - previewRows);
                        }
                    }
                }
            }
        }

        SampleBase.done("S24_TableExtract", produced.toArray(Path[]::new));
    }
}
