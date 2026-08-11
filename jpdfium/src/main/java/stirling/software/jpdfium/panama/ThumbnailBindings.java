package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for PDFium embedded page thumbnail extraction ({@code fpdf_thumbnail.h}).
 */
public final class ThumbnailBindings {

    private ThumbnailBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return Symbols.downcallCritical(name, desc);
    }

    public static final MethodHandle FPDFPage_GetDecodedThumbnailData = downcall("FPDFPage_GetDecodedThumbnailData",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFPage_GetRawThumbnailData = downcall("FPDFPage_GetRawThumbnailData",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFPage_GetThumbnailAsBitmap = downcallCritical("FPDFPage_GetThumbnailAsBitmap",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
}
