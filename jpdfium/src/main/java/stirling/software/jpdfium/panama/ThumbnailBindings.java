package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium embedded page thumbnail extraction ({@code fpdf_thumbnail.h}).
 */
public final class ThumbnailBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private ThumbnailBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("PDFium symbol not found: " + name)),
                desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("PDFium symbol not found: " + name)),
                desc, Linker.Option.critical(false));
    }

    public static final MethodHandle FPDFPage_GetDecodedThumbnailData = downcall("FPDFPage_GetDecodedThumbnailData",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFPage_GetRawThumbnailData = downcall("FPDFPage_GetRawThumbnailData",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFPage_GetThumbnailAsBitmap = downcallCritical("FPDFPage_GetThumbnailAsBitmap",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
}
