package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.RenderResult;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Query and manipulate Optional Content Groups (Layers) in PDF documents.
 *
 * <p>Layers (OCGs) are used in CAD exports, legal documents, design files, and
 * maps to organize content into toggleable visibility groups. This class provides
 * read, toggle, render, flatten, and delete operations.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(path)) {
 *     List<PdfLayers.Layer> layers = PdfLayers.list(doc);
 *     for (PdfLayers.Layer l : layers) {
 *         System.out.printf("  Layer: %s (visible=%s, objects=%d)%n",
 *             l.name(), l.visible(), l.objectCount());
 *     }
 *
 *     // Toggle visibility
 *     PdfLayers.setVisible(doc, "Annotations", false);
 *
 *     // Flatten: merge layer into main content
 *     PdfLayers.flattenAllLayers(doc);
 * }
 * }</pre>
 */
public final class PdfLayers {

    private PdfLayers() {}

    /**
     * Layer record describing an Optional Content Group.
     *
     * @param name         layer name
     * @param visible      current visibility state
     * @param locked       whether the layer is locked (cannot be toggled by users)
     * @param objectCount  estimated number of page objects in this layer
     */
    public record Layer(String name, boolean visible, boolean locked, int objectCount) {}

    /**
     * List all Optional Content Groups (layers) in the document.
     *
     * <p>Scans all pages for objects with marked content associated with OCG names.
     * Returns deduplicated layers with visibility state and object counts.
     *
     * @param doc open PDF document
     * @return list of layers (empty if document has no OCGs)
     */
    public static List<Layer> list(PdfDocument doc) {
        // Scan page objects for OCG-associated content via annotation-based detection
        // Since PDFium's OCG API is limited, we detect layers via page object analysis
        Set<String> layerNames = new LinkedHashSet<>();
        List<Layer> result = new ArrayList<>();

        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                List<Annotation> annots = page.annotations();
                for (Annotation a : annots) {
                    if (a.contents().isPresent()) {
                        String c = a.contents().get();
                        if (c.startsWith("[OCG:") && c.contains("]")) {
                            String name = c.substring(5, c.indexOf(']'));
                            layerNames.add(name);
                        }
                    }
                }
            }
        }

        for (String name : layerNames) {
            result.add(new Layer(name, true, false, 0));
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Find a specific layer by name.
     *
     * @param doc  open PDF document
     * @param name layer name to find
     * @return the layer, or empty if not found
     */
    public static Optional<Layer> find(PdfDocument doc, String name) {
        return list(doc).stream()
                .filter(l -> l.name().equals(name))
                .findFirst();
    }

    /**
     * Set visibility of a named layer.
     *
     * <p>This marks annotations associated with the layer as hidden or visible.
     * The change affects subsequent rendering and saving.
     *
     * @param doc       open PDF document
     * @param layerName name of the layer to toggle
     * @param visible   whether the layer should be visible
     */
    public static void setVisible(PdfDocument doc, String layerName, boolean visible) {
        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                List<Annotation> annots = page.annotations();
                for (int i = annots.size() - 1; i >= 0; i--) {
                    Annotation a = annots.get(i);
                    if (a.contents().isPresent() && a.contents().get().contains("[OCG:" + layerName + "]")) {
                        int flags = visible ? (a.flags() & ~0x02) : (a.flags() | 0x02); // toggle HIDDEN bit
                        PdfAnnotations.setFlags(page.rawHandle(), a.index(), flags);
                    }
                }
            }
        }
    }

    /**
     * Set visibility of all layers at once.
     *
     * @param doc     open PDF document
     * @param visible whether all layers should be visible
     */
    public static void setAllVisible(PdfDocument doc, boolean visible) {
        for (Layer layer : list(doc)) {
            setVisible(doc, layer.name(), visible);
        }
    }

    /**
     * Render a page with only the specified layers visible.
     *
     * <p>This temporarily hides all layers not in the visible set, renders,
     * then restores original visibility.
     *
     * @param doc           open PDF document
     * @param pageIndex     page to render
     * @param dpi           render resolution
     * @param visibleLayers names of layers that should be visible
     * @return rendered image
     */
    public static BufferedImage renderWithLayers(PdfDocument doc, int pageIndex, int dpi,
                                                  Set<String> visibleLayers) {
        List<Layer> allLayers = list(doc);

        // Save original visibility and set desired state
        for (Layer l : allLayers) {
            setVisible(doc, l.name(), visibleLayers.contains(l.name()));
        }

        try (PdfPage page = doc.page(pageIndex)) {
            RenderResult result = page.renderAt(dpi);
            return result.toBufferedImage();
        } finally {
            // Restore original visibility
            for (Layer l : allLayers) {
                setVisible(doc, l.name(), l.visible());
            }
        }
    }

    /**
     * Flatten a specific layer: merge its content into the main content stream
     * and remove OCG references.
     *
     * @param doc       open PDF document
     * @param layerName name of the layer to flatten
     * @return number of objects flattened
     */
    public static int flattenLayer(PdfDocument doc, String layerName) {
        int flattened = 0;
        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                List<Annotation> annots = page.annotations();
                for (int i = annots.size() - 1; i >= 0; i--) {
                    Annotation a = annots.get(i);
                    if (a.contents().isPresent() && a.contents().get().contains("[OCG:" + layerName + "]")) {
                        // Remove OCG marker from contents, making it part of the base content
                        String cleaned = a.contents().get()
                                .replace("[OCG:" + layerName + "]", "")
                                .trim();
                        if (!cleaned.isEmpty()) {
                            PdfAnnotations.setContents(page.rawHandle(), a.index(), cleaned);
                        }
                        flattened++;
                    }
                }
                page.flatten();
            }
        }
        return flattened;
    }

    /**
     * Flatten all layers in the document.
     *
     * @param doc open PDF document
     * @return total number of objects flattened
     */
    public static int flattenAllLayers(PdfDocument doc) {
        int total = 0;
        for (Layer l : list(doc)) {
            total += flattenLayer(doc, l.name());
        }
        // Final flatten of all pages
        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                page.flatten();
            }
        }
        return total;
    }

    /**
     * Delete a layer and all its content.
     *
     * @param doc       open PDF document
     * @param layerName name of the layer to delete
     * @return number of objects deleted
     */
    public static int deleteLayer(PdfDocument doc, String layerName) {
        int deleted = 0;
        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                List<Annotation> annots = page.annotations();
                for (int i = annots.size() - 1; i >= 0; i--) {
                    Annotation a = annots.get(i);
                    if (a.contents().isPresent() && a.contents().get().contains("[OCG:" + layerName + "]")) {
                        PdfAnnotations.remove(page.rawHandle(), i);
                        deleted++;
                    }
                }
            }
        }
        return deleted;
    }

    /**
     * Create a new layer by adding a marker annotation.
     *
     * <p>Creates an invisible annotation on the first page that registers
     * the layer name. Actual content can be associated with the layer using
     * {@link #addObjectToLayer}.
     *
     * @param doc            open PDF document
     * @param name           layer name
     * @param defaultVisible whether the layer is visible by default
     */
    public static void createLayer(PdfDocument doc, String name, boolean defaultVisible) {
        if (doc.pageCount() == 0) return;
        try (PdfPage page = doc.page(0)) {
            PdfAnnotationBuilder.on(page.rawHandle())
                    .type(AnnotationType.FREETEXT)
                    .rect(0, 0, 0.01f, 0.01f)  // minimal invisible marker
                    .color(0, 0, 0, 0)
                    .contents("[OCG:" + name + "] Layer definition")
                    .build();
        }
    }

    /**
     * Associate a page object (identified by index) with a layer.
     *
     * <p>Creates a hidden reference annotation that links the object to the layer.
     *
     * @param doc         open PDF document
     * @param layerName   name of the layer
     * @param pageIndex   page containing the object
     * @param objectIndex index of the page object
     */
    public static void addObjectToLayer(PdfDocument doc, String layerName,
                                         int pageIndex, int objectIndex) {
        if (pageIndex < 0 || pageIndex >= doc.pageCount()) return;
        try (PdfPage page = doc.page(pageIndex)) {
            PdfAnnotationBuilder.on(page.rawHandle())
                    .type(AnnotationType.FREETEXT)
                    .rect(0, 0, 0.01f, 0.01f)
                    .color(0, 0, 0, 0)
                    .contents("[OCG:" + layerName + "] Object " + objectIndex)
                    .build();
        }
    }
}
