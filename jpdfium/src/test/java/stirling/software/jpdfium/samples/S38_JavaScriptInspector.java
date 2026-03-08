package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.JavaScriptReport;
import stirling.software.jpdfium.doc.JsAction;
import stirling.software.jpdfium.doc.PdfJavaScriptInspector;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 38 - JavaScript Inspector.
 *
 * <p>Demonstrates PdfJavaScriptInspector: finding all JavaScript in a PDF document,
 * both document-level and annotation-level scripts.
 */
public class S38_JavaScriptInspector {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S38_JavaScriptInspector  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S38_JavaScriptInspector", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                List<MemorySegment> pages = new ArrayList<>();
                List<PdfPage> openPages = new ArrayList<>();
                try {
                    for (int p = 0; p < doc.pageCount(); p++) {
                        PdfPage page = doc.page(p);
                        openPages.add(page);
                        pages.add(page.rawHandle());
                    }

                    JavaScriptReport report = PdfJavaScriptInspector.inspect(doc.rawHandle(), pages);
                    System.out.printf("  Total scripts: %d  Code size: %d bytes%n",
                            report.totalScripts(), report.totalCodeSize());

                    for (JsAction js : report.documentScripts()) {
                        System.out.printf("    [DOC] \"%s\" (%d chars)%n",
                                js.name(), js.script().length());
                    }
                    for (JsAction js : report.annotationScripts()) {
                        System.out.printf("    [ANNOT] page %d annot %d trigger=%s (%d chars)%n",
                                js.pageIndex(), js.annotIndex(), js.trigger(), js.script().length());
                    }
                    if (!report.hasAnyScript()) {
                        System.out.println("  (no JavaScript)");
                    }
                } finally {
                    for (PdfPage page : openPages) page.close();
                }
            }
        }

        SampleBase.done("S38_JavaScriptInspector");
    }
}
