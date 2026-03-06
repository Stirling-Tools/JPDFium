package stirling.software.jpdfium.transform;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.RenderResult;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Page-level operations: flatten, convert to image, render to BufferedImage.
 *
 * <p><b>Usage Example</b></p>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     // Flatten all pages
 *     PageOps.flattenAll(doc);
 *
 *     // Convert specific page to image-based page
 *     PageOps.convertToImage(doc, 0, 300);
 *
 *     // Render page to BufferedImage
 *     BufferedImage img = PageOps.renderPage(doc, 0, 150);
 *
 *     doc.save(Path.of("output.pdf"));
 * }
 * }</pre>
 */
public final class PageOps {

    private PageOps() {}

    /**
     * Flatten all annotations on a specific page into the content stream.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     */
    public static void flatten(PdfDocument doc, int pageIndex) {
        try (PdfPage page = doc.page(pageIndex)) {
            page.flatten();
        }
    }

    /**
     * Flatten all pages in the document.
     *
     * @param doc open PDF document
     */
    public static void flattenAll(PdfDocument doc) {
        for (int i = 0; i < doc.pageCount(); i++) {
            flatten(doc, i);
        }
    }

    /**
     * Convert a page to an image-based page, removing all extractable text/vector content.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param dpi       render quality (150 = good, 300 = high quality)
     */
    public static void convertToImage(PdfDocument doc, int pageIndex, int dpi) {
        doc.convertPageToImage(pageIndex, dpi);
    }

    /**
     * Convert all pages to image-based pages.
     *
     * @param doc open PDF document
     * @param dpi render quality
     */
    public static void convertAllToImages(PdfDocument doc, int dpi) {
        for (int i = 0; i < doc.pageCount(); i++) {
            doc.convertPageToImage(i, dpi);
        }
    }

    /**
     * Render a page to a BufferedImage.
     *
     * @param doc       open PDF document
     * @param pageIndex zero-based page index
     * @param dpi       render DPI
     * @return rendered image
     */
    public static BufferedImage renderPage(PdfDocument doc, int pageIndex, int dpi) {
        try (PdfPage page = doc.page(pageIndex)) {
            RenderResult result = page.renderAt(dpi);
            return result.toBufferedImage();
        }
    }

    /**
     * Render all pages to BufferedImages.
     *
     * @param doc open PDF document
     * @param dpi render DPI
     * @return list of rendered images
     */
    public static List<BufferedImage> renderAll(PdfDocument doc, int dpi) {
        List<BufferedImage> images = new ArrayList<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            images.add(renderPage(doc, i, dpi));
        }
        return images;
    }
}
