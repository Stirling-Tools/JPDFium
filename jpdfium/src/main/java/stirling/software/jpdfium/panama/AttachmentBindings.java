package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for PDFium embedded file attachments ({@code fpdf_attachment.h}).
 */
public final class AttachmentBindings {

    private AttachmentBindings() {}

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return Symbols.downcallCritical(name, desc);
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
