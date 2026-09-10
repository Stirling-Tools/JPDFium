package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for the EmbedPDF fork's bookmark, action, and destination authoring APIs ({@code fpdf_doc.h}).
 */
public final class EmbedPdfBookmarkBindings {

    private EmbedPdfBookmarkBindings() {}

    /** Create a new top-level bookmark. */
    public static final MethodHandle EPDFBookmark_Create = Symbols.downcallOptional("EPDFBookmark_Create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** Create and append a new child bookmark under parent (or top-level if parent is NULL). */
    public static final MethodHandle EPDFBookmark_AppendChild = Symbols.downcallOptional("EPDFBookmark_AppendChild",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Create and insert a new child under parent right after after_sibling. */
    public static final MethodHandle EPDFBookmark_InsertAfter = Symbols.downcallOptional("EPDFBookmark_InsertAfter",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** Delete a bookmark subtree. */
    public static final MethodHandle EPDFBookmark_Delete = Symbols.downcallOptional("EPDFBookmark_Delete",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Set a bookmark's title. */
    public static final MethodHandle EPDFBookmark_SetTitle = Symbols.downcallOptional("EPDFBookmark_SetTitle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** Set a bookmark's target destination. */
    public static final MethodHandle EPDFBookmark_SetDest = Symbols.downcallOptional("EPDFBookmark_SetDest",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Set a bookmark's target action. */
    public static final MethodHandle EPDFBookmark_SetAction = Symbols.downcallOptional("EPDFBookmark_SetAction",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** Clear target destination and action from a bookmark. */
    public static final MethodHandle EPDFBookmark_ClearTarget = Symbols.downcallOptional("EPDFBookmark_ClearTarget",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Clear all bookmarks from a document. */
    public static final MethodHandle EPDFBookmark_Clear = Symbols.downcallOptional("EPDFBookmark_Clear",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Create an in-document XYZ destination: [page /XYZ left top zoom]. */
    public static final MethodHandle EPDFDest_CreateXYZ = Symbols.downcallOptional("EPDFDest_CreateXYZ",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_INT, JAVA_FLOAT, JAVA_INT, JAVA_FLOAT));

    /** Create an in-document view destination: [page /<View> params...]. */
    public static final MethodHandle EPDFDest_CreateView = Symbols.downcallOptional("EPDFDest_CreateView",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));

    /** Create a URI action. */
    public static final MethodHandle EPDFAction_CreateURI = Symbols.downcallOptional("EPDFAction_CreateURI",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /** Create an in-document GoTo action targeting dest. */
    public static final MethodHandle EPDFAction_CreateGoTo = Symbols.downcallOptional("EPDFAction_CreateGoTo",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
}
