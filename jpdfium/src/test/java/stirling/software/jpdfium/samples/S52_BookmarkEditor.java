package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfBookmarkEditor;
import stirling.software.jpdfium.doc.PdfBookmarkEditor.BookmarkTree;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 52 - Bookmark (Outline) Editor.
 *
 * <p>Demonstrates creating bookmarks manually and auto-generating them
 * from document headings using {@code PdfBookmarkEditor}.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S52_BookmarkEditor {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();
        Path outDir = SampleBase.out("S52_bookmark-editor");
        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        System.out.printf("S52_BookmarkEditor  |  %d PDF(s)%n", inputs.size());

        // 1. Manual bookmark tree
        SampleBase.section("Manual bookmarks");
        try (PdfDocument doc = PdfDocument.open(input)) {
            BookmarkTree tree = BookmarkTree.builder()
                    .add("Introduction", 0)
                    .add("Chapter 1", 0)
                    .build();

            byte[] result = PdfBookmarkEditor.setBookmarks(doc, tree);
            Path outFile = outDir.resolve(stem + "-manual-bookmarks.pdf");
            Files.write(outFile, result);
            produced.add(outFile);
            System.out.printf("  Created %d bookmarks -> %s%n",
                    tree.entries().size(), outFile.getFileName());
        }

        // 2. Auto-generate from headings
        SampleBase.section("Auto-generated from headings");
        try (PdfDocument doc = PdfDocument.open(input)) {
            BookmarkTree auto = PdfBookmarkEditor.fromHeadings(doc);
            if (!auto.entries().isEmpty()) {
                byte[] result = PdfBookmarkEditor.setBookmarks(doc, auto);
                Path outFile = outDir.resolve(stem + "-auto-bookmarks.pdf");
                Files.write(outFile, result);
                produced.add(outFile);
                System.out.printf("  Detected %d headings -> %s%n",
                        auto.entries().size(), outFile.getFileName());
            } else {
                System.out.println("  No headings detected (no large-font text found)");
            }
        }

        SampleBase.done("S52_BookmarkEditor", produced.toArray(Path[]::new));
    }
}
