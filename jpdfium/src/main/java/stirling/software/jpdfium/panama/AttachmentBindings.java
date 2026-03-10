package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium embedded file attachments ({@code fpdf_attachment.h}).
 */
public final class AttachmentBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private AttachmentBindings() {}

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

    public static final MethodHandle FPDFDoc_GetAttachmentCount = downcallCritical("FPDFDoc_GetAttachmentCount",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle FPDFDoc_AddAttachment = downcall("FPDFDoc_AddAttachment",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFDoc_GetAttachment = downcallCritical("FPDFDoc_GetAttachment",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFDoc_DeleteAttachment = downcall("FPDFDoc_DeleteAttachment",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    public static final MethodHandle FPDFAttachment_GetName = downcall("FPDFAttachment_GetName",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFAttachment_HasKey = downcall("FPDFAttachment_HasKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public static final MethodHandle FPDFAttachment_SetFile = downcall("FPDFAttachment_SetFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

    public static final MethodHandle FPDFAttachment_GetFile = downcall("FPDFAttachment_GetFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));

    public static final MethodHandle FPDFAttachment_GetStringValue = downcall("FPDFAttachment_GetStringValue",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
}
