package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium image object inspection ({@code fpdf_edit.h}).
 */
public final class ImageObjBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private ImageObjBindings() {}

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

    /**
     * Layout of FPDF_IMAGEOBJ_METADATA struct:
     * { unsigned int width, height, bits_per_pixel; int colorspace; int marked_content_id; }
     * Note: float horizontal_dpi, vertical_dpi also present in newer versions.
     * Total size is padded to 32 bytes to be safe.
     */
    public static final StructLayout IMAGE_METADATA_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_FLOAT.withName("horizontal_dpi"),
            JAVA_FLOAT.withName("vertical_dpi"),
            JAVA_INT.withName("bits_per_pixel"),
            JAVA_INT.withName("colorspace"),
            JAVA_INT.withName("marked_content_id"),
            JAVA_INT  // padding
    );

    /** Get the bitmap of an image object (original). */
    public static final MethodHandle FPDFImageObj_GetBitmap = downcall("FPDFImageObj_GetBitmap",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** Get rendered bitmap (with transforms applied). Needs doc + page + image_object. */
    public static final MethodHandle FPDFImageObj_GetRenderedBitmap = downcall("FPDFImageObj_GetRenderedBitmap",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Get raw (encoded) image data size. */
    public static final MethodHandle FPDFImageObj_GetImageDataRaw = downcall("FPDFImageObj_GetImageDataRaw",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Get decoded image data size. */
    public static final MethodHandle FPDFImageObj_GetImageDataDecoded = downcall("FPDFImageObj_GetImageDataDecoded",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Get image pixel dimensions. */
    public static final MethodHandle FPDFImageObj_GetImagePixelSize = downcall("FPDFImageObj_GetImagePixelSize",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Get image metadata (fills FPDF_IMAGEOBJ_METADATA struct). */
    public static final MethodHandle FPDFImageObj_GetImageMetadata = downcall("FPDFImageObj_GetImageMetadata",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Get the number of filters (compression) on an image. */
    public static final MethodHandle FPDFImageObj_GetImageFilterCount = downcallCritical("FPDFImageObj_GetImageFilterCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get a filter name by index. Returns chars written. */
    public static final MethodHandle FPDFImageObj_GetImageFilter = downcall("FPDFImageObj_GetImageFilter",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
}
