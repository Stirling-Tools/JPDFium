package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.Bookmark;

import java.nio.file.Path;
import java.util.List;

/**
 * SAMPLE 05 - Navigate PDF bookmark (outline) tree.
 *
 * <p>Demonstrates traversing the document outline (table of contents)
 * including nested bookmarks, destination pages, and URI actions.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S05_Bookmarks {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);

        System.out.printf("S05_Bookmarks  |  %d PDF(s)%n", inputs.size());

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S05_Bookmarks", input, fi + 1, inputs.size());

            try (PdfDocument doc = PdfDocument.open(input)) {
                List<Bookmark> bookmarks = doc.bookmarks();
                if (bookmarks.isEmpty()) {
                    System.out.println("  (no bookmarks)");
                } else {
                    System.out.printf("  %d top-level bookmark(s)%n", bookmarks.size());
                    for (Bookmark bm : bookmarks) {
                        printBookmark(bm, 2);
                    }
                }
            }
        }

        SampleBase.done("S05_Bookmarks");
    }

    private static void printBookmark(Bookmark bm, int indent) {
        String prefix = " ".repeat(indent);
        String dest = bm.isUri()
                ? "-> " + bm.uri().orElse("?")
                : "-> page " + bm.pageIndex();
        System.out.printf("%s\"%s\" %s%n", prefix, bm.title(), dest);
        for (Bookmark child : bm.children()) {
            printBookmark(child, indent + 2);
        }
    }
}
