package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium JavaScript action inspection ({@code fpdf_javascript.h}).
 */
public final class JavaScriptBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private JavaScriptBindings() {}

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

    /** Count document-level JavaScript actions. */
    public static final MethodHandle FPDFDoc_GetJavaScriptActionCount = downcallCritical("FPDFDoc_GetJavaScriptActionCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Get a JavaScript action handle by index. */
    public static final MethodHandle FPDFDoc_GetJavaScriptAction = downcall("FPDFDoc_GetJavaScriptAction",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    /** Get the name of a JavaScript action (UTF-16LE). Returns bytes written. */
    public static final MethodHandle FPDFJavaScriptAction_GetName = downcall("FPDFJavaScriptAction_GetName",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Get the script of a JavaScript action (UTF-16LE). Returns bytes written. */
    public static final MethodHandle FPDFJavaScriptAction_GetScript = downcall("FPDFJavaScriptAction_GetScript",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    /** Close a JavaScript action handle. */
    public static final MethodHandle FPDFDoc_CloseJavaScriptAction = downcall("FPDFDoc_CloseJavaScriptAction",
            FunctionDescriptor.ofVoid(ADDRESS));
}
