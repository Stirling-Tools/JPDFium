package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfWebLinks;
import stirling.software.jpdfium.doc.WebLink;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.text.PageText;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.TextLine;
import stirling.software.jpdfium.text.TextWord;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 39 - Web Links: full CRUD (Create, Read, Update, Delete).
 *
 * <p>
 * Demonstrates PdfWebLinks:
 * <ol>
 * <li><strong>Read</strong> - extract existing text-based URLs from all
 * pages</li>
 * <li><strong>Create</strong> - add link annotations on the first two words of
 * page 0 using real text positions from {@link PdfTextExtractor}. Links are
 * styled with blue underline by default (standard hyperlink appearance)</li>
 * <li><strong>Count</strong> - verify before/after link annotation counts</li>
 * <li><strong>Save</strong> - write the PDF with links; render page 0 to
 * PNG</li>
 * <li><strong>Delete</strong> - remove all link annotations from page 0</li>
 * <li><strong>Save</strong> - write the PDF without links; render page 0 to
 * PNG</li>
 * </ol>
 *
 * <p>
 * The addLink method creates visible hyperlinks by default with:
 * - Blue color (RGB: 0, 0, 255)
 * - Underline border style
 * - Auto-generated appearance stream
 *
 * <p>
 * Customize link appearance using the builder consumer:
 * {@code PdfWebLinks.addLink(page.rawHandle(), rect, url,
 *     builder -> builder.color(255, 0, 0)    // red
 *                 .borderWidth(2f)
 *                 .borderStyle(1)            // solid border
 *                 .generateAppearance()) }
 */
public class S39_WebLinks {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        Path outDir = SampleBase.out("S39_web-links");

        System.out.printf("S39_WebLinks  |  %d PDF(s)%n", inputs.size());

        Path input = inputs.getFirst();
        String stem = SampleBase.stem(input);

        try (PdfDocument doc = PdfDocument.open(input)) {

            // 1. READ: extract existing text-based URLs
            SampleBase.section("READ - extract text-based URLs");
            int totalLinks = 0;
            for (int p = 0; p < doc.pageCount(); p++) {
                try (PdfPage page = doc.page(p)) {
                    List<WebLink> links = PdfWebLinks.extract(page.rawHandle(), p);
                    for (WebLink link : links) {
                        System.out.printf("  Page %d: \"%s\" chars[%d..%d]%n",
                                link.pageIndex(), link.url(),
                                link.startCharIndex(), link.endCharIndex());
                        totalLinks++;
                    }
                }
            }
            System.out.printf("  Text-based URLs found: %d%n", totalLinks);

            // 2. CREATE: add links on the first two words of page 0
            SampleBase.section("CREATE - add links on first two words");
            List<Rect> linkRects = new ArrayList<>();
            try (PdfPage page = doc.page(0)) {
                int beforeCount = PdfWebLinks.countLinkAnnotations(page.rawHandle());
                System.out.printf("  Link annotations before: %d%n", beforeCount);

                // Find the first two words using text extraction (real page coords)
                PageText pageText = PdfTextExtractor.extractPage(doc, 0);
                List<TextWord> firstWords = new ArrayList<>();
                outer: for (TextLine line : pageText.lines()) {
                    for (TextWord word : line.words()) {
                        if (!word.text().isBlank()) {
                            firstWords.add(word);
                            if (firstWords.size() == 2)
                                break outer;
                        }
                    }
                }

                String[] urls = {
                        "https://github.com/Stirling-Tools/Stirling-PDF",
                        "https://github.com/Stirling-Tools/JPDFium"
                };

                if (firstWords.isEmpty()) {
                    // Fallback: fixed coordinates when no text is found
                    System.out.println("  (no words found - using fallback coordinates)");
                    Rect fallback = new Rect(72, 720, 200, 20);
                    // Uses default blue underline styling
                    int idx = PdfWebLinks.addLink(page.rawHandle(), fallback, urls[0]);
                    linkRects.add(fallback);
                    System.out.printf("  Added fallback link at index %d%n", idx);
                } else {
                    for (int i = 0; i < firstWords.size(); i++) {
                        TextWord word = firstWords.get(i);
                        // TextWord coords: x=left, y=bottom, in PDF points (origin bottom-left)
                        // Drop the underline 1.5 points below the text, and add 1 point of horizontal
                        // padding
                        float pX = 1.0f;
                        float pY = 1.5f;
                        Rect linkRect = new Rect(
                                word.x() - pX,
                                word.y() - pY,
                                word.width() + pX * 2,
                                word.height() + pY);
                        // Uses default blue underline styling (customizable via builder consumer)
                        int idx = PdfWebLinks.addLink(page.rawHandle(), linkRect, urls[i]);
                        linkRects.add(linkRect);
                        System.out.printf("  Word \"%s\" at (%.1f,%.1f,%.1f,%.1f) -> link idx=%d url=%s%n",
                                word.text(), word.x(), word.y(),
                                word.width(), word.height(), idx, urls[i]);
                    }
                }

                // Flush page content
                try {
                    PageEditBindings.FPDFPage_GenerateContent.invokeExact(page.rawHandle());
                } catch (Throwable ignored) {
                }

                int afterCount = PdfWebLinks.countLinkAnnotations(page.rawHandle());
                System.out.printf("  Link annotations after: %d (added %d)%n",
                        afterCount, afterCount - beforeCount);
            }

            // 3. SAVE with links + render PNG
            SampleBase.section("SAVE with links");
            Path withLinks = outDir.resolve(stem + "-with-links.pdf");
            doc.save(withLinks);
            System.out.printf("  Saved: %s%n", withLinks.getFileName());

            // Render page 0 to PNG to show link areas visually
            try (PdfDocument linked = PdfDocument.open(withLinks);
                    PdfPage p0 = linked.page(0)) {
                RenderResult render = p0.renderAt(150);
                Path pngWith = outDir.resolve(stem + "-with-links-p0.png");
                ImageIO.write(render.toBufferedImage(), "PNG", pngWith.toFile());
                System.out.printf("  Rendered: %s (%dx%d)%n",
                        pngWith.getFileName(), render.width(), render.height());
            }

            // 4. DELETE: remove all link annotations from page 0
            SampleBase.section("DELETE - remove all link annotations");
            try (PdfPage page = doc.page(0)) {
                int removed = PdfWebLinks.removeAllLinks(page.rawHandle());
                int remaining = PdfWebLinks.countLinkAnnotations(page.rawHandle());
                System.out.printf("  Removed %d link annotation(s), remaining: %d%n",
                        removed, remaining);
            }

            // 5. SAVE without links + render PNG
            SampleBase.section("SAVE without links");
            Path noLinks = outDir.resolve(stem + "-no-links.pdf");
            doc.save(noLinks);
            System.out.printf("  Saved: %s%n", noLinks.getFileName());

            try (PdfDocument unlinked = PdfDocument.open(noLinks);
                    PdfPage p0 = unlinked.page(0)) {
                RenderResult render = p0.renderAt(150);
                Path pngNo = outDir.resolve(stem + "-no-links-p0.png");
                ImageIO.write(render.toBufferedImage(), "PNG", pngNo.toFile());
                System.out.printf("  Rendered: %s (%dx%d)%n",
                        pngNo.getFileName(), render.width(), render.height());
            }

            SampleBase.done("S39_WebLinks", withLinks, noLinks);
        }
    }
}
