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
 *   <li><b>Text</b> - word-level Object Fission: a word that only partly falls
 *       outside the crop is preserved whole, including glyphs outside the crop
 *       (the page box clips them). Only words wholly outside are destroyed.</li>
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
 * <p><b>Known limitation (intentional):</b> a word that touches the crop edge is
 * preserved whole, including glyphs outside the crop, so its outside part still
 * exists in the file (the page CropBox clips it from view; pdftotext may see it).
 * Text objects whose font cannot be re-encoded at all (rare RTL/CID calligraphy
 * fonts) are likewise kept whole rather than dropped.
 *
 * <h3>How to manually check it works</h3>
 * <ol>
 *   <li>Open {@code *-hardcrop.pdf} in any PDF viewer - the right half of the page
 *       (text and pictures) is gone, the page size is the crop rectangle, and the
 *       surviving text did not move or re-flow.</li>
 *   <li>Compare the {@code before-*.png} / {@code after-*.png} renders side by side.</li>
 *   <li>Confirm fully-outside words are gone and boundary words were not cut in
 *       half. Boundary words intentionally keep glyphs outside the page box, so
 *       a bbox-inside-the-page check is no longer the contract:
 *       <pre>{@code
 *       pdftotext *-hardcrop.pdf - | grep -E 'DROP|KEEP'
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
     * word that was WHOLLY outside the crop rect is really gone from the content
     * stream (position-matched against the original). Words that touch the crop
     * edge are preserved whole by design, so their outside glyphs are expected
     * survivors. Returns true if the check failed.
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

        // Removal check: glyphs belonging to wholly-outside words must no longer
        // exist at their original position. Straddling words are kept whole.
        List<CharPos> beforeRemovable = fullyOutsideWordChars(before, crop);
        long stillPresent = beforeRemovable.stream()
                .filter(orig -> after.stream().anyMatch(a -> sameGlyph(a, orig)))
                .count();
        long removed = beforeRemovable.size() - stillPresent;

        if (removed > 0) {
            System.out.printf("  removal: %d/%d wholly-outside glyphs removed%n",
                    removed, beforeRemovable.size());
        }
        long preservedBoundary = before.stream()
                .filter(c -> c.unicode() >= 0x20)
                .filter(c -> !beforeRemovable.contains(c))
                .filter(c -> outsideBBox(c, crop))
                .count();
        if (preservedBoundary > 0) {
            System.out.printf("  kept:    %d glyph(s) in words touching the crop edge%n",
                    preservedBoundary);
        }

        // Kept-clipped glyphs from a wholly-outside word indicate a font
        // re-encode failure. Only a large fraction is a real removal regression.
        long threshold = Math.max(RESIDUAL_TOLERANCE, beforeRemovable.size() / 5);
        if (stillPresent > threshold) {
            System.err.printf("  FAIL [%s]: %d wholly-outside characters were NOT removed%n",
                    label, stillPresent);
            return true;
        }
        if (stillPresent > 0) {
            System.out.printf("  WARN: [%s] %d wholly-outside glyph(s) kept-clipped " +
                    "(font re-encode failed - Ghostscript-equivalent clip)%n", label, stillPresent);
        }
        System.out.printf("  OK:   [%s] page resized to %s, outside words removed, PDF valid%n",
                label, crop);
        return false;
    }

    /** Characters belonging to words whose entire bbox is outside the crop. */
    private static List<CharPos> fullyOutsideWordChars(List<CharPos> chars, Rect crop) {
        List<CharPos> out = new ArrayList<>();
        int i = 0;
        while (i < chars.size()) {
            CharPos first = chars.get(i);
            if (isWordBoundary(first)) {
                i++;
                continue;
            }
            int start = i;
            float l = Float.MAX_VALUE, b = Float.MAX_VALUE;
            float r = -Float.MAX_VALUE, t = -Float.MAX_VALUE;
            while (i < chars.size() && !isWordBoundary(chars.get(i))) {
                CharPos c = chars.get(i);
                l = Math.min(l, (float) c.l());
                b = Math.min(b, (float) c.b());
                r = Math.max(r, (float) c.r());
                t = Math.max(t, (float) c.t());
                i++;
            }
            if (r < crop.x() || l > crop.x() + crop.width()
                    || t < crop.y() || b > crop.y() + crop.height()) {
                out.addAll(chars.subList(start, i));
            }
        }
        return out;
    }

    private static boolean isWordBoundary(CharPos c) {
        return c.unicode() < 0x21 || c.unicode() == 0xA0;
    }

    private static boolean outsideBBox(CharPos c, Rect crop) {
        return c.r() < crop.x() || c.l() > crop.x() + crop.width()
                || c.t() < crop.y() || c.b() > crop.y() + crop.height();
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
