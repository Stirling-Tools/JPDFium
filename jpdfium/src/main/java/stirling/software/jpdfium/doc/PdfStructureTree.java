package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.StructureBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Navigate the tagged structure tree of a PDF page.
 *
 * <p>The structure tree provides accessibility information (tags) that describe
 * the logical reading order and document semantics (paragraphs, headings,
 * tables, etc.).
 *
 * <pre>{@code
 * try (var doc = PdfDocument.open(path);
 *      var page = doc.openPage(0)) {
 *     MemorySegment rawPage = JpdfiumLib.pageRawHandle(page.nativeHandle());
 *     List<StructElement> tree = PdfStructureTree.get(rawPage);
 *     for (StructElement e : tree) {
 *         System.out.printf("  %s: %s%n", e.type(),
 *             e.altText().orElse(""));
 *     }
 * }
 * }</pre>
 */
public final class PdfStructureTree {

    private static final int MAX_DEPTH = 100;

    private PdfStructureTree() {}

    /**
     * Get the structure tree for a page.
     *
     * @param page raw FPDF_PAGE segment
     * @return the top-level structure elements, empty list if no structure tree
     */
    public static List<StructElement> get(MemorySegment page) {
        MemorySegment tree;
        try {
            tree = (MemorySegment) StructureBindings.FPDF_StructTree_GetForPage.invokeExact(page);
        } catch (Throwable t) { throw new RuntimeException("FPDF_StructTree_GetForPage failed", t); }

        if (tree.equals(MemorySegment.NULL)) {
            return Collections.emptyList();
        }

        try {
            int count;
            try {
                count = (int) StructureBindings.FPDF_StructTree_CountChildren.invokeExact(tree);
            } catch (Throwable t) { throw new RuntimeException(t); }

            if (count <= 0) return Collections.emptyList();

            List<StructElement> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                MemorySegment child;
                try {
                    child = (MemorySegment) StructureBindings.FPDF_StructTree_GetChildAtIndex.invokeExact(tree, i);
                } catch (Throwable t) { throw new RuntimeException(t); }

                if (!child.equals(MemorySegment.NULL)) {
                    result.add(buildElement(child, 0));
                }
            }
            return Collections.unmodifiableList(result);
        } finally {
            try {
                StructureBindings.FPDF_StructTree_Close.invokeExact(tree);
            } catch (Throwable t) { /* close is best-effort */ }
        }
    }

    private static StructElement buildElement(MemorySegment elem, int depth) {
        String type = getWideStringProp(elem, StructureBindings.FPDF_StructElement_GetType);
        Optional<String> title = optWideStringProp(elem, StructureBindings.FPDF_StructElement_GetTitle);
        Optional<String> altText = optWideStringProp(elem, StructureBindings.FPDF_StructElement_GetAltText);
        Optional<String> id = optWideStringProp(elem, StructureBindings.FPDF_StructElement_GetID);
        Optional<String> lang = optWideStringProp(elem, StructureBindings.FPDF_StructElement_GetLang);

        List<StructElement> children;
        if (depth >= MAX_DEPTH) {
            children = Collections.emptyList();
        } else {
            int count;
            try {
                count = (int) StructureBindings.FPDF_StructElement_CountChildren.invokeExact(elem);
            } catch (Throwable t) { throw new RuntimeException(t); }

            if (count <= 0) {
                children = Collections.emptyList();
            } else {
                children = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    MemorySegment child;
                    try {
                        child = (MemorySegment) StructureBindings.FPDF_StructElement_GetChildAtIndex.invokeExact(elem, i);
                    } catch (Throwable t) { throw new RuntimeException(t); }
                    if (!child.equals(MemorySegment.NULL)) {
                        children.add(buildElement(child, depth + 1));
                    }
                }
            }
        }

        return new StructElement(type, title, altText, id, lang, children);
    }

    private static String getWideStringProp(MemorySegment elem, MethodHandle getter) {
        try (Arena arena = Arena.ofConfined()) {
            long needed;
            try {
                needed = (long) getter.invokeExact(elem, MemorySegment.NULL, 0L);
            } catch (Throwable t) { throw new RuntimeException(t); }
            if (needed <= 2) return "";

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) getter.invokeExact(elem, buf, needed);
            } catch (Throwable t) { throw new RuntimeException(t); }
            return FfmHelper.fromWideString(buf, needed);
        }
    }

    private static Optional<String> optWideStringProp(MemorySegment elem, MethodHandle getter) {
        String val = getWideStringProp(elem, getter);
        return val.isEmpty() ? Optional.empty() : Optional.of(val);
    }
}
