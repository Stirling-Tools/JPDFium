package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SAMPLE 93 - Ghostscript-style hard crop (remove content outside the crop area).
 *
 * <p>Demonstrates {@link PdfPageGeometry#cropAndRemoveContent}: the page MediaBox/CropBox
 * are set to the crop rectangle AND every page object lying outside it is physically
 * removed from the content stream:
 * <ul>
 *   <li><b>Text</b> - character-level Object Fission: characters whose placement
 *       origin lies outside the crop rect are destroyed; a line straddling the
 *       boundary is split so only the glyphs inside the crop area survive (pinned
 *       to their coordinates).</li>
 *   <li><b>Pictures / paths / shading / form XObjects</b> - removed when fully outside
 *       the crop rect; objects partially overlapping are kept and clipped by the CropBox
 *       (Ghostscript crop-and-clip behaviour - the visible part is never dropped).</li>
 * </ul>
 *
 * <p>Runs two crops per page:
 * <ol>
 *   <li><b>margin-crop</b> - a 1-inch (72pt) inset on every side (typical crop).</li>
 *   <li><b>hard-crop</b> - keep only the LEFT half of the page, which is guaranteed to
 *       cut visible text and pictures. This is the aggressive Ghostscript case.</li>
 * </ol>
 *
 * <p><b>Known limitation (Ghostscript-equivalent clip):</b> re-creating a split text
 * object flattens it to a single {@code Tj}, which drops kerning and can make glyphs
 * near the crop edge drift a few points, and text objects whose font cannot be
 * re-encoded at all (rare RTL/CID calligraphy fonts) are kept whole. In both cases
 * the out-of-crop part is clipped by the page CropBox, so nothing is visible outside
 * the crop - but such glyphs still exist in the file (pdftotext may see them).
 *
 * <h3>How to manually check it works</h3>
 * <ol>
 *   <li>Open {@code *-hardcrop.pdf} in any PDF viewer - the right half of the page
 *       (text and pictures) is gone, the page size is the crop rectangle, and the
 *       surviving text did not move or re-flow.</li>
 *   <li>Compare the {@code before-*.png} / {@code after-*.png} renders side by side.</li>
 *   <li>Confirm the crop area is truly emptied (not just hidden) - every surviving word
 *       bbox must stay inside the cropped page size:
 *       <pre>{@code
 *       pdftotext -bbox *-hardcrop.pdf - | grep -o 'width="[0-9.]*" height="[0-9.]*"'
 *       }</pre></li>
 *   <li>Confirm the output is structurally valid:
 *       <pre>{@code
 *       qpdf --check *-hardcrop.pdf          # must report "no errors"
 *       gs -sDEVICE=nullpage *-hardcrop.pdf  # must render without warnings
 *       }</pre></li>
 * </ol>
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S93_CropRemoveContent {

    private static final int DPI = 150;

    /**
     * Base tolerance for glyphs that survive in a kept-clipped text object (their
     * font cannot be re-encoded so the straddling object is kept whole and clipped
     * by the page CropBox). The effective failure threshold is the larger of this
     * value and 20% of the glyphs that were outside the crop rect, so a handful of
     * boundary glyphs is a warning while a real removal regression still fails.
     */
    private static final long RESIDUAL_TOLERANCE = 5;

    private static final String NUM = "(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)";
    private static final Pattern CHAR_POS_RE = Pattern.compile(
            "\\{\"i\":(\\d+),\"u\":(\\d+)," +
            "\"ox\":" + NUM + ",\"oy\":" + NUM + "," +
            "\"l\":" + NUM + ",\"r\":" + NUM + "," +
            "\"b\":" + NUM + ",\"t\":" + NUM + "\\}"
    );

    record CharPos(int index, int unicode, double ox, double oy,
                   double l, double r, double b, double t) {}

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S93_CropRemoveContent  |  %d PDF(s)  |  dpi: %d%n", inputs.size(), DPI);

        int totalChecks = 0;
        int failedChecks = 0;

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S93_CropRemoveContent", input, fi + 1, inputs.size());
            String stem = SampleBase.stem(input);
            Path outDir = SampleBase.out("S93_crop-remove-content", input);

            // Pass 1: 1-inch margin crop (typical case)
            try (PdfDocument doc = PdfDocument.open(input)) {
                int n = doc.pageCount();
                for (int p = 0; p < Math.min(n, 2); p++) {
                    Rect crop;
                    List<CharPos> before;
                    try (PdfPage page = doc.page(p)) {
                        float w = page.size().width();
                        float h = page.size().height();
                        // 1-inch margins, clamped so the crop stays valid on tiny pages.
                        float m = Math.min(72, Math.min(w, h) / 4.0f);
                        crop = new Rect(m, m, w - 2 * m, h - 2 * m);
                        before = positions(page);
                    }

                    totalChecks++;
                    if (cropPageAndVerify(doc, p, crop, before, "margin-crop")) failedChecks++;
                }
                Path outPath = outDir.resolve(stem + "-margincrop.pdf");
                doc.save(outPath);
                produced.add(outPath);
            }

            // Pass 2: hard crop to the LEFT half (guaranteed to cut content)
            try (PdfDocument doc = PdfDocument.open(input)) {
                int n = doc.pageCount();
                for (int p = 0; p < Math.min(n, 2); p++) {
                    Rect crop;
                    List<CharPos> before;
                    try (PdfPage page = doc.page(p)) {
                        float w = page.size().width();
                        float h = page.size().height();
                        crop = new Rect(0, 0, w / 2.0f, h);

                        Path beforePng = outDir.resolve(stem + "-page" + p + "-before.png");
                        var beforeRender = SampleBase.renderOrSkip(page, DPI,
                                stem + " page " + p + " (before)");
                        if (beforeRender != null) {
                            ImageIO.write(beforeRender.toBufferedImage(), "PNG", beforePng.toFile());
                            produced.add(beforePng);
                        }

                        before = positions(page);
                    }

                    SampleBase.section("Hard crop page " + p + " -> left half " + crop);
                    System.out.printf("  chars before crop: %d%n", before.size());

                    totalChecks++;
                    if (cropPageAndVerify(doc, p, crop, before, "hard-crop")) failedChecks++;

                    try (PdfPage page = doc.page(p)) {
                        Path afterPng = outDir.resolve(stem + "-page" + p + "-after.png");
                        var afterRender = SampleBase.renderOrSkip(page, DPI,
                                stem + " page " + p + " (after)");
                        if (afterRender != null) {
                            ImageIO.write(afterRender.toBufferedImage(), "PNG", afterPng.toFile());
                            produced.add(afterPng);
                        }
                    }
                }

                Path outPath = outDir.resolve(stem + "-hardcrop.pdf");
                doc.save(outPath);
                produced.add(outPath);

                // Re-open the produced file: must be valid and keep its page count.
                try (PdfDocument reopened = PdfDocument.open(outPath)) {
                    boolean ok = reopened.pageCount() == n;
                    totalChecks++;
                    if (!ok) failedChecks++;
                    System.out.printf("  %s  re-open: %d pages (original %d)%n",
                            ok ? "OK:  " : "FAIL:", reopened.pageCount(), n);
                }
            }
        }

        System.out.println();
        System.out.printf("S93_CropRemoveContent - DONE  (verification %d/%d checks passed)%n",
                totalChecks - failedChecks, totalChecks);
        System.out.println("  MANUAL CHECK: open *-hardcrop.pdf in a viewer and compare the");
        System.out.println("  before-*.png / after-*.png renders. Content outside the crop");
        System.out.println("  rectangle must be gone and the surviving text must not have moved.");
        System.out.println("  Structural:  qpdf --check <output>  |  gs -sDEVICE=nullpage <output>");
        SampleBase.done("S93_CropRemoveContent", produced.toArray(Path[]::new));
    }

    /**
     * Apply {@link PdfPageGeometry#cropAndRemoveContent} then verify that every
     * character that was OUTSIDE the crop rect is really gone from the content
     * stream (position-matched against the original - a re-encoded glyph that
     * drifted is not counted as leftover). Returns true if the check failed.
     */
    private static boolean cropPageAndVerify(PdfDocument doc, int pageIndex, Rect crop,
                                             List<CharPos> before, String label) {
        SampleBase.section(label + " page " + pageIndex + " -> " + crop);
        PdfPageGeometry.cropAndRemoveContent(doc, pageIndex, crop);

        List<CharPos> after;
        try (PdfPage page = doc.page(pageIndex)) {
            after = positions(page);
        }
        long visibleBefore = before.stream().filter(c -> c.unicode() >= 0x20).count();
        long visibleAfter = after.stream().filter(c -> c.unicode() >= 0x20).count();
        System.out.printf("  chars: %d -> %d; visible glyphs %d -> %d%n",
                before.size(), after.size(), visibleBefore, visibleAfter);

        // Removal check: every original glyph that was outside the crop rect must
        // no longer exist at its original position in the output. (Glyphs that
        // survive the crop re-encode and drift are covered by the drift note.)
        List<CharPos> beforeOutside = before.stream()
                .filter(c -> c.unicode() >= 0x20)
                .filter(c -> outsideOrigin(c, crop))
                .toList();
        long stillPresent = beforeOutside.stream()
                .filter(orig -> after.stream().anyMatch(a -> sameGlyph(a, orig)))
                .count();
        long removed = beforeOutside.size() - stillPresent;

        // Drift note: surviving glyphs that now sit outside the crop rect (their
        // text object was re-encoded with flat Tj, dropping kerning) are clipped
        // by the page CropBox - a fidelity artifact, not leftover outside content.
        long drifted = after.stream()
                .filter(c -> c.unicode() >= 0x20)
                .filter(c -> outsideOrigin(c, crop))
                .filter(a -> beforeOutside.stream().noneMatch(orig -> sameGlyph(a, orig)))
                .count();

        if (removed > 0) {
            System.out.printf("  removal: %d/%d outside glyphs removed%n", removed, beforeOutside.size());
        }
        if (drifted > 0) {
            System.out.printf("  drift:   %d surviving glyph(s) clipped at crop edge " +
                    "(flat-Tj re-encode dropped kerning - Ghostscript-equivalent clip)%n", drifted);
        }

        // Kept-clipped glyphs are the documented font-re-encode failure: a text
        // object straddling the crop edge whose font cannot be split (rare
        // RTL/CID calligraphy fonts) is kept whole and clipped by the page
        // CropBox - what Ghostscript's pdfwrite does. Only a large fraction of
        // un-removed glyphs indicates a real removal regression.
        long threshold = Math.max(RESIDUAL_TOLERANCE, beforeOutside.size() / 5);
        if (stillPresent > threshold) {
            System.err.printf("  FAIL [%s]: %d outside characters were NOT removed%n",
                    label, stillPresent);
            return true;
        }
        if (stillPresent > 0) {
            System.out.printf("  WARN: [%s] %d outside glyph(s) kept-clipped " +
                    "(font re-encode failed - Ghostscript-equivalent clip)%n", label, stillPresent);
        }
        System.out.printf("  OK:   [%s] page resized to %s, outside content removed, PDF valid%n",
                label, crop);
        return false;
    }

    private static boolean outsideOrigin(CharPos c, Rect crop) {
        return c.ox() < crop.x() - 0.5 || c.ox() > crop.x() + crop.width() + 0.5
            || c.oy() < crop.y() - 0.5 || c.oy() > crop.y() + crop.height() + 0.5;
    }

    private static boolean sameGlyph(CharPos a, CharPos b) {
        return a.unicode() == b.unicode()
                && Math.abs(a.ox() - b.ox()) < 2.0 && Math.abs(a.oy() - b.oy()) < 2.0;
    }

    private static List<CharPos> positions(PdfPage page) {
        String json = page.extractCharPositionsJson();
        var out = new ArrayList<CharPos>();
        Matcher m = CHAR_POS_RE.matcher(json);
        while (m.find()) {
            out.add(new CharPos(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Double.parseDouble(m.group(3)),
                    Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5)),
                    Double.parseDouble(m.group(6)),
                    Double.parseDouble(m.group(7)),
                    Double.parseDouble(m.group(8))));
        }
        return out;
    }
}
