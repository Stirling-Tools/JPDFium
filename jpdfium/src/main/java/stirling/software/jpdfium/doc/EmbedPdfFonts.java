package stirling.software.jpdfium.doc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.EmbedPdfFontBindings;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.NativeGuard;

/**
 * Registered fonts for PDF authoring: register a font program once, then
 * FreeText annotations and fallback lookup resolve it by id.
 */
public final class EmbedPdfFonts {

    /** fsType embedding permission of a registered font. */
    public static final int PERMISSION_INSTALLABLE = 0;
    public static final int PERMISSION_EDITABLE = 1;
    public static final int PERMISSION_PREVIEW_AND_PRINT = 2;
    public static final int PERMISSION_RESTRICTED = 3;
    public static final int PERMISSION_BITMAP_ONLY = 4;

    /** Embedding policy for appearances generated after the call. */
    public static final int POLICY_DEFAULT = 0;
    public static final int POLICY_SUBSET = 1;
    public static final int POLICY_FULL = 2;

    private EmbedPdfFonts() {}

    private static void requireAvailable(Object handle, String name) {
        // Registry calls are document-less, so init would otherwise never run.
        JpdfiumLib.ensureInitialized();
        if (handle == null) throw new JPDFiumException(name + " not in this native build");
    }

    /**
     * Register an in-memory font program. Null family/0 weight infer from
     * the font; pass -1 italic to infer slant.
     *
     * @return non-zero font id
     */
    public static int registerMemFont(String family, int weight, int italic, byte[] fontBytes) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_RegisterMemFont64, "EPDFFont_RegisterMemFont64");
        if (fontBytes == null || fontBytes.length == 0) throw new IllegalArgumentException("fontBytes must not be empty");
        NativeGuard.acquire();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment familySeg = family == null || family.isEmpty()
                    ? MemorySegment.NULL : arena.allocateFrom(family);
            MemorySegment dataSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, fontBytes);
            int id = (int) EmbedPdfFontBindings.EPDFFont_RegisterMemFont64.invokeExact(
                    familySeg, weight, italic, dataSeg, (long) fontBytes.length);
            if (id == 0) throw new JPDFiumException("font registration refused the program");
            return id;
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Drop all registered fonts and the fallback order. */
    public static void clearRegisteredFonts() {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_ClearRegisteredFonts, "EPDFFont_ClearRegisteredFonts");
        NativeGuard.acquire();
        try {
            EmbedPdfFontBindings.EPDFFont_ClearRegisteredFonts.invokeExact();
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** fsType permission of a registered font, -1 for unknown id. */
    public static int embeddingPermission(int fontId) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_GetEmbeddingPermission, "EPDFFont_GetEmbeddingPermission");
        NativeGuard.acquire();
        try {
            return (int) EmbedPdfFontBindings.EPDFFont_GetEmbeddingPermission.invokeExact(fontId);
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Whether new text may be authored with the font. */
    public static boolean isEditingAuthorized(int fontId) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_IsEditingAuthorized, "EPDFFont_IsEditingAuthorized");
        NativeGuard.acquire();
        try {
            return (int) EmbedPdfFontBindings.EPDFFont_IsEditingAuthorized.invokeExact(fontId) != 0;
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Assert a licence permitting editing with the font. */
    public static boolean authorizeEditing(int fontId) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_AuthorizeEditing, "EPDFFont_AuthorizeEditing");
        NativeGuard.acquire();
        try {
            return (int) EmbedPdfFontBindings.EPDFFont_AuthorizeEditing.invokeExact(fontId) != 0;
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Resolved family name, empty for unknown id. */
    public static Optional<String> familyName(int fontId) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_GetFamilyName, "EPDFFont_GetFamilyName");
        NativeGuard.acquire();
        try (Arena arena = Arena.ofConfined()) {
            long len = (long) EmbedPdfFontBindings.EPDFFont_GetFamilyName.invokeExact(
                    fontId, MemorySegment.NULL, 0L);
            if (len <= 1) return Optional.empty();
            MemorySegment buf = arena.allocate(len);
            long got = (long) EmbedPdfFontBindings.EPDFFont_GetFamilyName.invokeExact(fontId, buf, len);
            if (got <= 1) return Optional.empty();
            byte[] bytes = buf.reinterpret(got).toArray(ValueLayout.JAVA_BYTE);
            int end = bytes.length;
            while (end > 0 && bytes[end - 1] == 0) end--;
            return Optional.of(new String(bytes, 0, end, StandardCharsets.UTF_8));
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Resolved weight (100..900), 0 for unknown id. */
    public static int weight(int fontId) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_GetWeight, "EPDFFont_GetWeight");
        NativeGuard.acquire();
        try {
            return (int) EmbedPdfFontBindings.EPDFFont_GetWeight.invokeExact(fontId);
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Resolved slant, false for unknown id. */
    public static boolean isItalic(int fontId) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_IsItalic, "EPDFFont_IsItalic");
        NativeGuard.acquire();
        try {
            return (int) EmbedPdfFontBindings.EPDFFont_IsItalic.invokeExact(fontId) != 0;
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Whether the program is a pinned instance of a variable font. */
    public static boolean isInstanced(int fontId) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_IsInstanced, "EPDFFont_IsInstanced");
        NativeGuard.acquire();
        try {
            return (int) EmbedPdfFontBindings.EPDFFont_IsInstanced.invokeExact(fontId) != 0;
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Set how much of a registered font generated appearances carry. */
    public static void setEmbeddingPolicy(MemorySegment rawDoc, int policy) {
        requireAvailable(EmbedPdfFontBindings.EPDFDoc_SetFontEmbeddingPolicy, "EPDFDoc_SetFontEmbeddingPolicy");
        NativeGuard.acquire();
        try {
            int ok = (int) EmbedPdfFontBindings.EPDFDoc_SetFontEmbeddingPolicy.invokeExact(rawDoc, policy);
            if (ok == 0) throw new JPDFiumException("embedding policy rejected");
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Current embedding policy, -1 for invalid document. */
    public static int embeddingPolicy(MemorySegment rawDoc) {
        requireAvailable(EmbedPdfFontBindings.EPDFDoc_GetFontEmbeddingPolicy, "EPDFDoc_GetFontEmbeddingPolicy");
        NativeGuard.acquire();
        try {
            return (int) EmbedPdfFontBindings.EPDFDoc_GetFontEmbeddingPolicy.invokeExact(rawDoc);
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Append a registered font to the fallback order. */
    public static void addFallbackFont(int fontId) {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_AddFallbackFont, "EPDFFont_AddFallbackFont");
        NativeGuard.acquire();
        try {
            int ok = (int) EmbedPdfFontBindings.EPDFFont_AddFallbackFont.invokeExact(fontId);
            if (ok == 0) throw new JPDFiumException("fallback font rejected");
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }

    /** Clear the fallback order without unregistering fonts. */
    public static void clearFallbackFonts() {
        requireAvailable(EmbedPdfFontBindings.EPDFFont_ClearFallbackFonts, "EPDFFont_ClearFallbackFonts");
        NativeGuard.acquire();
        try {
            EmbedPdfFontBindings.EPDFFont_ClearFallbackFonts.invokeExact();
        } catch (Throwable t) { throw new JPDFiumException(t); }
        finally { NativeGuard.release(); }
    }
}
