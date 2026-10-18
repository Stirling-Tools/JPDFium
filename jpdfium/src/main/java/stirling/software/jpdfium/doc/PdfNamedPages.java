package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.EmbedPdfNamedPageBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PdfiumRuntime;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * High-level API for EmbedPDF named pages and templates in the document catalog
 * ({@code /Names /Pages} and {@code /Names /Templates}).
 */
public final class PdfNamedPages {

    public enum Tree {
        PAGES(EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_TREE_PAGES),
        TEMPLATES(EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_TREE_TEMPLATES);

        final int nativeVal;

        Tree(int nativeVal) {
            this.nativeVal = nativeVal;
        }
    }

    public enum Kind {
        PAGE,
        TEMPLATE,
        DANGLING;

        static Kind fromNative(int val) {
            return switch (val) {
                case EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_KIND_PAGE -> PAGE;
                case EmbedPdfNamedPageBindings.EPDF_NAMED_PAGE_KIND_TEMPLATE -> TEMPLATE;
                default -> DANGLING;
            };
        }
    }

    public record NamedPageEntry(String name, int objectNumber, Kind kind) {}

    private PdfNamedPages() {}

    public static boolean isSupported() {
        JpdfiumLib.ensureInitialized();
        return EmbedPdfNamedPageBindings.EPDFDoc_GetNamedPageCount != null
                && EmbedPdfNamedPageBindings.EPDFDoc_GetNamedPageAt != null
                && EmbedPdfNamedPageBindings.EPDFDoc_SetNamedPage != null
                && EmbedPdfNamedPageBindings.EPDFDoc_RemoveNamedPage != null
                && EmbedPdfNamedPageBindings.EPDFDoc_RemoveNamedPagesForPage != null;
    }

    private static void checkSupported() {
        if (!isSupported()) {
            throw new JPDFiumException("Named pages API is not supported in this native build");
        }
    }

    /**
     * Get the count of named page registrations in the given tree.
     */
    public static int count(MemorySegment rawDoc, Tree tree) {
        checkSupported();
        Objects.requireNonNull(tree, "tree");
        if (rawDoc == null || rawDoc.equals(MemorySegment.NULL)) return 0;
        return PdfiumRuntime.execute(() -> {
            try {
                int c = (int) EmbedPdfNamedPageBindings.EPDFDoc_GetNamedPageCount.invokeExact(rawDoc, tree.nativeVal);
                return Math.max(0, c);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException("Failed to get named page count", t);
            }
        });
    }

    /**
     * List all entries in the given named page tree.
     */
    public static List<NamedPageEntry> list(MemorySegment rawDoc, Tree tree) {
        checkSupported();
        Objects.requireNonNull(tree, "tree");
        if (rawDoc == null || rawDoc.equals(MemorySegment.NULL)) return Collections.emptyList();

        return PdfiumRuntime.execute(() -> {
            int c;
            try {
                c = (int) EmbedPdfNamedPageBindings.EPDFDoc_GetNamedPageCount.invokeExact(rawDoc, tree.nativeVal);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException("Failed to get named page count", t);
            }
            if (c <= 0) return Collections.emptyList();

            List<NamedPageEntry> result = new ArrayList<>(c);
            for (int i = 0; i < c; i++) {
                try (Arena arena = Arena.ofConfined()) {
                    long req = (long) EmbedPdfNamedPageBindings.EPDFDoc_GetNamedPageAt.invokeExact(
                            rawDoc, tree.nativeVal, i, MemorySegment.NULL, 0L, MemorySegment.NULL, MemorySegment.NULL);
                    // req <= 2 means the native side returned only the null terminator (or
                    // nothing) for this slot; the entry is corrupt or reserved – skip it.
                    // The caller receives a shorter list than count(); that is documented.
                    if (req <= 2) continue;

                    MemorySegment buf = arena.allocate(req);
                    MemorySegment objNumSeg = arena.allocate(ValueLayout.JAVA_INT);
                    MemorySegment kindSeg = arena.allocate(ValueLayout.JAVA_INT);

                    long written = (long) EmbedPdfNamedPageBindings.EPDFDoc_GetNamedPageAt.invokeExact(
                            rawDoc, tree.nativeVal, i, buf, req, objNumSeg, kindSeg);
                    if (written > 0) {
                        String name = FfmHelper.fromWideString(buf, written);
                        int objNum = objNumSeg.get(ValueLayout.JAVA_INT, 0);
                        int kindVal = kindSeg.get(ValueLayout.JAVA_INT, 0);
                        result.add(new NamedPageEntry(name, objNum, Kind.fromNative(kindVal)));
                    }
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    throw new JPDFiumException("Failed to read named page entry at index " + i, t);
                }
            }
            return result;
        });
    }

    /**
     * Register a name to a page's indirect object number in {@code /Names /Pages}.
     */
    public static boolean setNamedPage(MemorySegment rawDoc, String name, int pageObjectNumber) {
        checkSupported();
        if (rawDoc == null || rawDoc.equals(MemorySegment.NULL) || name == null || name.isEmpty() || pageObjectNumber <= 0) {
            return false;
        }
        return PdfiumRuntime.execute(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment nameSeg = FfmHelper.toWideString(arena, name);
                int ok = (int) EmbedPdfNamedPageBindings.EPDFDoc_SetNamedPage.invokeExact(rawDoc, nameSeg, pageObjectNumber);
                return ok != 0;
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException("Failed to set named page '" + name + "'", t);
            }
        });
    }

    /**
     * Remove a named page registration by name.
     */
    public static boolean removeNamedPage(MemorySegment rawDoc, String name) {
        checkSupported();
        if (rawDoc == null || rawDoc.equals(MemorySegment.NULL) || name == null || name.isEmpty()) {
            return false;
        }
        return PdfiumRuntime.execute(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment nameSeg = FfmHelper.toWideString(arena, name);
                int ok = (int) EmbedPdfNamedPageBindings.EPDFDoc_RemoveNamedPage.invokeExact(rawDoc, nameSeg);
                return ok != 0;
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException("Failed to remove named page '" + name + "'", t);
            }
        });
    }

    /**
     * Remove all registrations for a specific page object number.
     */
    public static int removeNamedPagesForPage(MemorySegment rawDoc, int pageObjectNumber) {
        checkSupported();
        if (rawDoc == null || rawDoc.equals(MemorySegment.NULL) || pageObjectNumber <= 0) {
            return 0;
        }
        return PdfiumRuntime.execute(() -> {
            try {
                return (int) EmbedPdfNamedPageBindings.EPDFDoc_RemoveNamedPagesForPage.invokeExact(rawDoc, pageObjectNumber);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException("Failed to remove named pages for page object " + pageObjectNumber, t);
            }
        });
    }

    /**
     * Return the indirect object number of the page at the given zero-based index.
     * Returns {@code 0} when the native build does not export
     * {@code EPDFDoc_GetPageObjectNumberByIndex} or when the lookup fails.
     */
    public static int getPageObjectNumber(MemorySegment rawDoc, int pageIndex) {
        if (rawDoc == null || rawDoc.equals(MemorySegment.NULL) || pageIndex < 0) return 0;
        MethodHandle mh = EmbedPdfNamedPageBindings.EPDFDoc_GetPageObjectNumberByIndex;
        if (mh == null) return 0;
        JpdfiumLib.ensureInitialized();
        return PdfiumRuntime.execute(() -> {
            try {
                return (int) mh.invokeExact(rawDoc, pageIndex);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException("Failed to get page object number for index " + pageIndex, t);
            }
        });
    }
}
