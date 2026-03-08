package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium rendering with flags and color schemes ({@code fpdfview.h}).
 */
public final class RenderBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private RenderBindings() {}

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

    // Render flag constants
    public static final int FPDF_ANNOT               = 0x01;
    public static final int FPDF_LCD_TEXT             = 0x02;
    public static final int FPDF_GRAYSCALE            = 0x08;
    public static final int FPDF_REVERSE_BYTE_ORDER   = 0x10;
    public static final int FPDF_PRINTING             = 0x800;
    public static final int FPDF_RENDER_NO_SMOOTHTEXT = 0x1000;
    public static final int FPDF_RENDER_NO_SMOOTHIMAGE= 0x2000;
    public static final int FPDF_RENDER_NO_SMOOTHPATH = 0x4000;

    /** Layout of FPDF_COLORSCHEME: 4 DWORDs (path_fill, path_stroke, text_fill, text_stroke). */
    public static final StructLayout COLORSCHEME_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("path_fill_color"),
            JAVA_INT.withName("path_stroke_color"),
            JAVA_INT.withName("text_fill_color"),
            JAVA_INT.withName("text_stroke_color")
    );

    /** Create FPDF_BITMAP. alpha: 0=no alpha (BGRx), 1=with alpha (BGRA). */
    public static final MethodHandle FPDFBitmap_Create = downcall("FPDFBitmap_Create",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));

    /** Fill a rectangle in the bitmap. color: 0xAARRGGBB. */
    public static final MethodHandle FPDFBitmap_FillRect = downcall("FPDFBitmap_FillRect",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));

    /** Render page to bitmap with flags. */
    public static final MethodHandle FPDF_RenderPageBitmap = downcall("FPDF_RenderPageBitmap",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    /** Render page with color scheme (progressive). */
    public static final MethodHandle FPDF_RenderPageBitmapWithColorScheme_Start = downcall(
            "FPDF_RenderPageBitmapWithColorScheme_Start",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    /** Continue progressive rendering. Returns status: 0=ready, 1=to-be-continued, 2=done, 3=failed. */
    public static final MethodHandle FPDF_RenderPage_Continue = downcall("FPDF_RenderPage_Continue",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Close/clean up progressive rendering context for a page. */
    public static final MethodHandle FPDF_RenderPage_Close = downcall("FPDF_RenderPage_Close",
            FunctionDescriptor.ofVoid(ADDRESS));

    /** Convert device coordinates to page coordinates. */
    public static final MethodHandle FPDF_DeviceToPage = downcall("FPDF_DeviceToPage",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    /** Convert page coordinates to device coordinates. */
    public static final MethodHandle FPDF_PageToDevice = downcall("FPDF_PageToDevice",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS, ADDRESS));
}
