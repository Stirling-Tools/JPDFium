package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.FillResult;
import stirling.software.jpdfium.doc.FormField;
import stirling.software.jpdfium.doc.PdfFormFiller;
import stirling.software.jpdfium.doc.PdfFormReader;
import stirling.software.jpdfium.model.RenderResult;

import javax.imageio.ImageIO;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SAMPLE 59 - Form Filling.
 *
 * <p>Demonstrates {@link PdfFormFiller}: fills text fields with distinctive values,
 * checks checkboxes, selects radio buttons and combo/list options. Renders BEFORE and
 * AFTER PNGs so the result is visually obvious.
 *
 * <p>Checkboxes and radio buttons are toggled via a simulated mouse click
 * ({@code FORM_OnLButtonDown} / {@code FORM_OnLButtonUp}), which is the only
 * approach that correctly writes PDF Name values for {@code /V} and {@code /AS}.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S59_FormFill {

    private static final int RENDER_DPI = 150;

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S59_form_fill");

        System.out.println("S59_FormFill  |  Fill text, checkboxes, radio, combos - renders BEFORE/AFTER PNGs");
        System.out.println();

        // 1. Find form PDFs
        List<Path> allInputs = SampleBase.inputPdfs(args);
        List<Path> formPdfs = allInputs.stream()
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.contains("form") || n.contains("irs");
                })
                .toList();
        List<Path> inputs = formPdfs.isEmpty()
                ? allInputs.subList(0, Math.min(3, allInputs.size()))
                : formPdfs;

        if (inputs.isEmpty()) {
            System.out.println("  (no PDFs found)");
            SampleBase.done("S59_FormFill", produced.toArray(Path[]::new));
            return;
        }

        // 2. Process each PDF
        for (Path input : inputs) {
            System.out.printf("== %s ==%n", input.getFileName());

            // Discover fields
            List<FormField> fields = readFields(input);
            if (fields.isEmpty()) {
                System.out.println("  (no form fields - skipping)");
                System.out.println();
                continue;
            }

            System.out.printf("  Fields discovered (%d):%n", fields.size());
            for (FormField f : fields) {
                System.out.printf("    [%-10s] name=%-20s val=%-15s%s%n",
                        f.type(), quoted(f.name()), quoted(f.value()),
                        f.readOnly() ? " [read-only]" : "");
                if (!f.options().isEmpty()) {
                    System.out.printf("               options: %s%n", f.options());
                }
            }

            // Render BEFORE pages
            String stem = SampleBase.stem(input);
            List<Path> beforePngs = renderPages(input, outDir, stem + "-BEFORE-page");
            produced.addAll(beforePngs);
            System.out.printf("  BEFORE: rendered %d page(s) to PNG%n", beforePngs.size());

            // Build fill map with obvious, distinctive values
            Map<String, String> fillMap = buildObviousFillMap(fields);
            if (fillMap.isEmpty()) {
                System.out.println("  (all fields read-only - nothing to fill)");
                System.out.println();
                continue;
            }
            System.out.println("  Fill map:");
            for (Map.Entry<String, String> e : fillMap.entrySet()) {
                System.out.printf("    %-22s -> %s%n", quoted(e.getKey()), quoted(e.getValue()));
            }

            // Fill and save
            Path filledPath = outDir.resolve(stem + "-FILLED.pdf");
            FillResult result;
            try (PdfDocument doc = PdfDocument.open(input)) {
                result = PdfFormFiller.fill(doc)
                        .fromMap(fillMap)
                        .apply();
                doc.save(filledPath);
            }
            produced.add(filledPath);
            System.out.printf("  Fill result: %s%n", result.summary());

            // Render AFTER pages
            List<Path> afterPngs = renderPages(filledPath, outDir, stem + "-AFTER-page");
            produced.addAll(afterPngs);
            System.out.printf("  AFTER:  rendered %d page(s) to PNG%n", afterPngs.size());

            // Read back and show values
            List<FormField> after = readFields(filledPath);
            System.out.println("  Values AFTER fill:");
            for (FormField f : after) {
                if (f.readOnly()) continue;
                System.out.printf("    [%-10s] name=%-20s val=%s%n",
                        f.type(), quoted(f.name()), quoted(f.value()));
            }

            System.out.println();
        }

        // 3. Flatten + fill demo
        SampleBase.section("Fill from map, then flatten (bakes values into content)");
        for (Path input : inputs) {
            List<FormField> fields = readFields(input);
            Map<String, String> fillMap = buildObviousFillMap(fields);
            if (fillMap.isEmpty()) continue;

            String stem = SampleBase.stem(input);
            Path flatPath = outDir.resolve(stem + "-FILLED-FLATTENED.pdf");
            FillResult result;
            try (PdfDocument doc = PdfDocument.open(input)) {
                result = PdfFormFiller.fill(doc)
                        .fromMap(fillMap)
                        .flatten()
                        .apply();
                doc.save(flatPath);
            }
            produced.add(flatPath);
            System.out.printf("  %s: %s (flattened %d page(s))%n",
                    input.getFileName(), result.summary(), result.flattenedPages());

            // Render the flattened pages - field values should be burned in
            List<Path> flatPngs = renderPages(flatPath, outDir, stem + "-FLATTENED-page");
            produced.addAll(flatPngs);
        }

        SampleBase.done("S59_FormFill", produced.toArray(Path[]::new));
    }

    // Helpers

    /** Read all form fields from a PDF. */
    private static List<FormField> readFields(Path pdf) throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<MemorySegment> rawPages = new ArrayList<>();
            List<PdfPage> openPages = new ArrayList<>();
            try {
                for (int p = 0; p < doc.pageCount(); p++) {
                    PdfPage page = doc.page(p);
                    openPages.add(page);
                    rawPages.add(page.rawHandle());
                }
                return PdfFormReader.readAll(doc.rawHandle(), rawPages);
            } finally {
                for (PdfPage p : openPages) p.close();
            }
        }
    }

    /** Render all pages of a PDF to PNG files; returns the produced paths. */
    private static List<Path> renderPages(Path pdf, Path outDir, String prefix) throws Exception {
        List<Path> pngs = new ArrayList<>();
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    RenderResult rr = page.renderAt(RENDER_DPI);
                    Path png = outDir.resolve(prefix + i + ".png");
                    ImageIO.write(rr.toBufferedImage(), "PNG", png.toFile());
                    pngs.add(png);
                }
            }
        }
        return pngs;
    }

    /**
     * Build a fill map with highly visible, non-default values so that
     * changes are unmistakable when comparing BEFORE and AFTER PNGs.
     *
     * <ul>
     *   <li>Text fields - obvious text with field name and year
     *   <li>Checkboxes  - "Yes" (triggers the check state)
     *   <li>Radio       - first non-default export value
     *   <li>Combo/List  - last available option (avoids the factory default at index 0)
     * </ul>
     */
    private static Map<String, String> buildObviousFillMap(List<FormField> fields) {
        Map<String, String> map = new LinkedHashMap<>();
        for (FormField f : fields) {
            if (f.readOnly()) continue;
            switch (f.type()) {
                case TEXT -> {
                    String label = f.name().isEmpty() ? "FIELD" : f.name().toUpperCase();
                    map.put(f.name(), "*** " + label + " JPDFIUM-2026 ***");
                }
                case CHECKBOX -> map.put(f.name(), "Yes");
                case RADIO    -> {
                    // pick a non-empty export value so the click targets the right button
                    if (!f.exportValue().isEmpty()) {
                        map.putIfAbsent(f.name(), f.exportValue());
                    }
                }
                case COMBOBOX, LISTBOX -> {
                    if (!f.options().isEmpty()) {
                        // pick the LAST option to make the change obvious vs. default first
                        map.put(f.name(), f.options().getLast());
                    }
                }
                default -> {}
            }
        }
        return map;
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }
}
