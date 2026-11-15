package stirling.software.jpdfium.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Optional FFM bindings for the PoDoFo bridge.
 *
 * <p>PoDoFo is not required to load JPDFium. Missing symbols return null /
 * {@link Optional#empty()} and callers keep their existing fallback path.
 */
public final class PodofoLib {

    private static final MethodHandle AVAILABLE = Symbols.downcallOptional(
            "jpdfium_podofo_available", FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle REPAIR = Symbols.downcallOptional(
            "jpdfium_podofo_repair",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

    private static final MethodHandle EMBED_FONT_DATA = Symbols.downcallOptional(
            "jpdfium_podofo_embed_font_data",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS,
                    JAVA_LONG, ADDRESS, ADDRESS));

    private static final MethodHandle DEDUP_IMAGES = Symbols.downcallOptional(
            "jpdfium_podofo_dedup_images",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle REPLACE_FONT_DATA = Symbols.downcallOptional(
            "jpdfium_podofo_replace_font_data",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT,
                    ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

    private PodofoLib() {}

    /** True when the native library was built with PoDoFo. */
    public static boolean isAvailable() {
        if (AVAILABLE == null) return false;
        try {
            return (int) AVAILABLE.invokeExact() != 0;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return false;
        }
    }

    /**
     * Re-save a PDF through PoDoFo as a repair fallback.
     *
     * @return repaired bytes, or null when PoDoFo is absent or refuses the file
     */
    public static byte[] repair(byte[] input) {
        if (input == null || input.length == 0 || REPAIR == null) return null;
        return PdfiumRuntime.execute(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment in = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment outPtr = arena.allocate(ADDRESS);
                MemorySegment outLen = arena.allocate(JAVA_LONG);
                try {
                    int rc = (int) REPAIR.invokeExact(in, (long) input.length, outPtr, outLen);
                    return copyOutput(rc, outPtr, outLen);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    return null;
                }
            }
        });
    }

    /**
     * Replace the stream behind an existing font descriptor.
     *
     * @param input    PDF bytes
     * @param fontNum  object number of the font dictionary
     * @param fontGen  generation of the font dictionary
     * @param fontData TrueType/OpenType/CFF bytes that match the dictionary
     * @return rewritten PDF, or null when PoDoFo is absent or the font has no file
     */
    public static byte[] replaceFontData(byte[] input, long fontNum, int fontGen, byte[] fontData) {
        if (input == null || input.length == 0 || fontData == null || fontData.length == 0
                || REPLACE_FONT_DATA == null) {
            return null;
        }
        return PdfiumRuntime.execute(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment in = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment font = arena.allocateFrom(JAVA_BYTE, fontData);
                MemorySegment outPtr = arena.allocate(ADDRESS);
                MemorySegment outLen = arena.allocate(JAVA_LONG);
                try {
                    int rc = (int) REPLACE_FONT_DATA.invokeExact(
                            in, (long) input.length, fontNum, fontGen, font,
                            (long) fontData.length, outPtr, outLen);
                    return copyOutput(rc, outPtr, outLen);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    return null;
                }
            }
        });
    }

    /**
     * Replace font program data for every font whose {@code /BaseFont} matches.
     * The caller must provide a program compatible with the existing encoding,
     * widths and glyph mapping; this is not a font-substitution engine.
     */
    public static byte[] embedFontData(byte[] input, String baseFont, byte[] fontData) {
        if (input == null || input.length == 0 || baseFont == null || baseFont.isBlank()
                || fontData == null || fontData.length == 0 || EMBED_FONT_DATA == null) {
            return null;
        }
        return PdfiumRuntime.execute(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment in = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment name = arena.allocateFrom(baseFont);
                MemorySegment font = arena.allocateFrom(JAVA_BYTE, fontData);
                MemorySegment outPtr = arena.allocate(ADDRESS);
                MemorySegment outLen = arena.allocate(JAVA_LONG);
                try {
                    int rc = (int) EMBED_FONT_DATA.invokeExact(
                            in, (long) input.length, name, font, (long) fontData.length,
                            outPtr, outLen);
                    return copyOutput(rc, outPtr, outLen);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    return null;
                }
            }
        });
    }

    /**
     * Remove duplicate image XObjects and rewrite references. Returns the
     * rewritten PDF; the native bridge reports how many objects were removed
     * through a diagnostics field in the future API, but this method only
     * returns bytes so the normal compressor path stays byte-oriented.
     */
    public static byte[] dedupImages(byte[] input) {
        if (input == null || input.length == 0 || DEDUP_IMAGES == null) return null;
        return PdfiumRuntime.execute(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment in = arena.allocateFrom(JAVA_BYTE, input);
                MemorySegment outPtr = arena.allocate(ADDRESS);
                MemorySegment outLen = arena.allocate(JAVA_LONG);
                MemorySegment removed = arena.allocate(JAVA_INT);
                try {
                    int rc = (int) DEDUP_IMAGES.invokeExact(
                            in, (long) input.length, outPtr, outLen, removed);
                    return copyOutput(rc, outPtr, outLen);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    return null;
                }
            }
        });
    }

    private static byte[] copyOutput(int rc, MemorySegment outPtr, MemorySegment outLen) {
        if (rc < 0) return null;
        MemorySegment ptr = outPtr.get(ADDRESS, 0);
        long len = outLen.get(JAVA_LONG, 0);
        if (len <= 0) return null;
        byte[] bytes = ptr.reinterpret(len).toArray(JAVA_BYTE);
        JpdfiumH.jpdfium_free_buffer(ptr);
        return bytes;
    }
}
