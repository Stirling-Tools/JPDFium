package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.FormField;
import stirling.software.jpdfium.doc.PdfFormReader;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 30 - Form Field Reader.
 *
 * <p>Demonstrates PdfFormReader: reading form fields from widget annotations
 * including field types, names, values, options, and checked state.
 */
public class S30_FormReader {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S30_FormReader  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S30_FormReader", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                List<MemorySegment> pages = new ArrayList<>();
                List<PdfPage> openPages = new ArrayList<>();
                try {
                    for (int p = 0; p < doc.pageCount(); p++) {
                        PdfPage page = doc.page(p);
                        openPages.add(page);
                        pages.add(page.rawHandle());
                    }

                    List<FormField> fields = PdfFormReader.readAll(doc.rawHandle(), pages);
                    if (fields.isEmpty()) {
                        System.out.println("  (no form fields)");
                    } else {
                        System.out.printf("  %d form field(s):%n", fields.size());
                        for (FormField f : fields) {
                            System.out.printf("    page %d: [%s] \"%s\" = \"%s\"%s%n",
                                    f.pageIndex(), f.type(), f.name(), f.value(),
                                    f.checked() ? " (checked)" : "");
                            if (!f.options().isEmpty()) {
                                System.out.printf("      options: %s selected: %s%n",
                                        f.options(), f.selectedOptionIndices());
                            }
                        }
                    }
                } finally {
                    for (PdfPage page : openPages) page.close();
                }
            }
        }

        SampleBase.done("S30_FormReader");
    }
}
