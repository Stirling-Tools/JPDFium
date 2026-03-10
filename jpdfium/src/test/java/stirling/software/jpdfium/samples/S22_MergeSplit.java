package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfMerge;
import stirling.software.jpdfium.PdfSplit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SAMPLE 22 - Merge and Split PDFs.
 *
 * <p>Demonstrates the high-level merge/split API built on top of PdfPageImporter.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S22_MergeSplit {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S22_MergeSplit  |  %d PDF(s)%n", inputs.size());
        Path outDir = SampleBase.out("S22_merge-split");

        // 1. Merge all inputs into one document
        if (inputs.size() >= 2) {
            SampleBase.section("Merge all PDFs");
            List<PdfDocument> docs = new ArrayList<>();
            try {
                for (Path p : inputs) {
                    docs.add(PdfDocument.open(p));
                }
                try (PdfDocument merged = PdfMerge.merge(docs)) {
                    Path outFile = outDir.resolve("merged-all.pdf");
                    merged.save(outFile);
                    produced.add(outFile);
                    System.out.printf("  Merged %d docs -> %d pages -> %s%n",
                            inputs.size(), merged.pageCount(), outFile.getFileName());
                }
            } finally {
                for (PdfDocument d : docs) d.close();
            }
        }

        // 2. Merge from file paths
        if (inputs.size() >= 2) {
            SampleBase.section("Merge from file paths");
            try (PdfDocument merged = PdfMerge.mergeFiles(inputs)) {
                Path outFile = outDir.resolve("merged-files.pdf");
                merged.save(outFile);
                produced.add(outFile);
                System.out.printf("  Merged %d files -> %d pages%n",
                        inputs.size(), merged.pageCount());
            }
        }

        // 3. Split first PDF into single pages
        SampleBase.section("Split into single pages");
        try (PdfDocument doc = PdfDocument.open(inputs.getFirst())) {
            List<PdfDocument> parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.singlePages());
            try {
                System.out.printf("  Split %s into %d single-page docs%n",
                        inputs.getFirst().getFileName(), parts.size());
                for (int i = 0; i < parts.size(); i++) {
                    Path outFile = outDir.resolve(SampleBase.stem(inputs.getFirst())
                            + "-page-" + (i + 1) + ".pdf");
                    parts.get(i).save(outFile);
                    produced.add(outFile);
                }
            } finally {
                for (PdfDocument p : parts) p.close();
            }
        }

        // 4. Split every 2 pages
        SampleBase.section("Split every 2 pages");
        try (PdfDocument doc = PdfDocument.open(inputs.getFirst())) {
            if (doc.pageCount() >= 2) {
                List<PdfDocument> parts = PdfSplit.split(doc,
                        PdfSplit.SplitStrategy.everyNPages(2));
                try {
                    System.out.printf("  Split into %d chunks of ≤2 pages%n", parts.size());
                    for (int i = 0; i < parts.size(); i++) {
                        Path outFile = outDir.resolve(SampleBase.stem(inputs.getFirst())
                                + "-chunk-" + (i + 1) + ".pdf");
                        parts.get(i).save(outFile);
                        produced.add(outFile);
                    }
                } finally {
                    for (PdfDocument p : parts) p.close();
                }
            } else {
                System.out.println("  (skipped - need ≥2 pages)");
            }
        }

        // 5. Extract specific pages
        SampleBase.section("Extract specific pages");
        try (PdfDocument doc = PdfDocument.open(inputs.getFirst())) {
            Set<Integer> indices = Set.of(0); // first page
            try (PdfDocument extracted = PdfSplit.extractPages(doc, indices)) {
                Path outFile = outDir.resolve(SampleBase.stem(inputs.getFirst())
                        + "-extracted-p1.pdf");
                extracted.save(outFile);
                produced.add(outFile);
                System.out.printf("  Extracted page(s) %s -> %d pages%n",
                        indices, extracted.pageCount());
            }
        }

        // 6. Split by bookmarks
        SampleBase.section("Split by bookmarks");
        try (PdfDocument doc = PdfDocument.open(inputs.getFirst())) {
            List<PdfDocument> parts = PdfSplit.split(doc,
                    PdfSplit.SplitStrategy.byBookmarks());
            try {
                System.out.printf("  Split by bookmarks -> %d parts%n", parts.size());
                for (int i = 0; i < parts.size(); i++) {
                    Path outFile = outDir.resolve(SampleBase.stem(inputs.getFirst())
                            + "-bm-" + (i + 1) + ".pdf");
                    parts.get(i).save(outFile);
                    produced.add(outFile);
                }
            } finally {
                for (PdfDocument p : parts) p.close();
            }
        }

        SampleBase.done("S22_MergeSplit", produced.toArray(Path[]::new));
    }
}
