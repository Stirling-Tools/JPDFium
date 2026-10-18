package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for EmbedPDF font registration ({@code epdf_font.h}).
 */
public final class EmbedPdfFontBindings {

    private EmbedPdfFontBindings() {}

    private static MethodHandle downcallOptional(String name, FunctionDescriptor desc) {
        return Symbols.downcallOptional(name, desc);
    }

    public static final MethodHandle EPDFFont_RegisterMemFont64 = downcallOptional(
            "EPDFFont_RegisterMemFont64", FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));

    public static final MethodHandle EPDFFont_ClearRegisteredFonts = downcallOptional(
            "EPDFFont_ClearRegisteredFonts", FunctionDescriptor.ofVoid());

    public static final MethodHandle EPDFFont_GetEmbeddingPermission = downcallOptional(
            "EPDFFont_GetEmbeddingPermission", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    public static final MethodHandle EPDFFont_IsEditingAuthorized = downcallOptional(
            "EPDFFont_IsEditingAuthorized", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    public static final MethodHandle EPDFFont_AuthorizeEditing = downcallOptional(
            "EPDFFont_AuthorizeEditing", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    public static final MethodHandle EPDFFont_IsInstanced = downcallOptional(
            "EPDFFont_IsInstanced", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    public static final MethodHandle EPDFFont_GetFamilyName = downcallOptional(
            "EPDFFont_GetFamilyName", FunctionDescriptor.of(JAVA_LONG,
                    JAVA_INT, ADDRESS, JAVA_LONG));

    public static final MethodHandle EPDFFont_GetWeight = downcallOptional(
            "EPDFFont_GetWeight", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    public static final MethodHandle EPDFFont_IsItalic = downcallOptional(
            "EPDFFont_IsItalic", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    public static final MethodHandle EPDFDoc_SetFontEmbeddingPolicy = downcallOptional(
            "EPDFDoc_SetFontEmbeddingPolicy", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    public static final MethodHandle EPDFDoc_GetFontEmbeddingPolicy = downcallOptional(
            "EPDFDoc_GetFontEmbeddingPolicy", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle EPDFFont_AddFallbackFont = downcallOptional(
            "EPDFFont_AddFallbackFont", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    public static final MethodHandle EPDFFont_ClearFallbackFonts = downcallOptional(
            "EPDFFont_ClearFallbackFonts", FunctionDescriptor.ofVoid());
}
