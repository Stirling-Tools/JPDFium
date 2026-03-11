package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bindings for PDFium interactive form filling ({@code fpdf_formfill.h}).
 *
 * <p>These bindings cover the write-side of form filling:
 * <ul>
 *   <li>Page lifecycle notifications ({@code FORM_OnAfterLoadPage}, {@code FORM_OnBeforeClosePage})</li>
 *   <li>Text field editing ({@code FORM_SetFocusedAnnot}, {@code FORM_SelectAllText},
 *       {@code FORM_ReplaceSelection})</li>
 *   <li>List/combo selection ({@code FORM_SetIndexSelected})</li>
 *   <li>Focus management ({@code FORM_ForceToKillFocus})</li>
 * </ul>
 *
 * <p>The page lifecycle calls ({@code FORM_OnAfterLoadPage} / {@code FORM_OnBeforeClosePage})
 * are <strong>mandatory</strong>: omitting them causes silent failures where values are not
 * persisted on save.
 */
public final class FormFillBindings {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    private FormFillBindings() {}

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
     * {@code FORM_OnAfterLoadPage(FPDF_PAGE page, FPDF_FORMHANDLE hHandle) -> void}.
     *
     * <p>Must be called immediately after opening a page (via {@code FPDF_LoadPage} or
     * equivalent) when a form fill environment is active. Notifies the form system that
     * the page is live.
     */
    public static final MethodHandle FORM_OnAfterLoadPage = downcall("FORM_OnAfterLoadPage",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /**
     * {@code FORM_OnBeforeClosePage(FPDF_PAGE page, FPDF_FORMHANDLE hHandle) -> void}.
     *
     * <p>Must be called immediately before closing a page. Allows the form system to
     * flush pending changes and clean up page-level state.
     */
    public static final MethodHandle FORM_OnBeforeClosePage = downcall("FORM_OnBeforeClosePage",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /**
     * {@code FORM_SetFocusedAnnot(FPDF_FORMHANDLE handle, FPDF_ANNOTATION annot) -> FPDF_BOOL}.
     *
     * <p>Programmatically focuses an annotation. For text fields, this must be called before
     * {@link #FORM_SelectAllText} and {@link #FORM_ReplaceSelection} to route input to the
     * correct field.
     */
    public static final MethodHandle FORM_SetFocusedAnnot = downcall("FORM_SetFocusedAnnot",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * {@code FORM_SelectAllText(FPDF_FORMHANDLE hHandle, FPDF_PAGE page) -> FPDF_BOOL}.
     *
     * <p>Selects all text in the currently focused text field. Call this before
     * {@link #FORM_ReplaceSelection} to replace any existing content.
     */
    public static final MethodHandle FORM_SelectAllText = downcall("FORM_SelectAllText",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * {@code FORM_ReplaceSelection(FPDF_FORMHANDLE hHandle, FPDF_PAGE page,
     * FPDF_WIDESTRING wsText) -> void}.
     *
     * <p>Replaces the current selection with the given UTF-16LE text. Used after
     * {@link #FORM_SetFocusedAnnot} and {@link #FORM_SelectAllText} to set a text field
     * value while also regenerating its appearance stream.
     */
    public static final MethodHandle FORM_ReplaceSelection = downcall("FORM_ReplaceSelection",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code FORM_SetIndexSelected(FPDF_FORMHANDLE hHandle, FPDF_PAGE page, int index,
     * FPDF_BOOL selected) -> FPDF_BOOL}.
     *
     * <p>Selects or deselects a list item at the given zero-based index. Works for combo
     * boxes and list boxes. The {@code page} parameter must be the raw FPDF_PAGE handle.
     */
    public static final MethodHandle FORM_SetIndexSelected = downcall("FORM_SetIndexSelected",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    /**
     * {@code FORM_OnLButtonDown(FPDF_FORMHANDLE hHandle, FPDF_PAGE page, uint32_t modifier,
     * double page_x, double page_y) -> void}.
     *
     * <p>Simulates a left mouse-button press at the given page coordinates. Used together with
     * {@link #FORM_OnLButtonUp} to toggle checkboxes and select radio buttons programmatically.
     */
    public static final MethodHandle FORM_OnLButtonDown = downcall("FORM_OnLButtonDown",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE));

    /**
     * {@code FORM_OnLButtonUp(FPDF_FORMHANDLE hHandle, FPDF_PAGE page, uint32_t modifier,
     * double page_x, double page_y) -> void}.
     *
     * <p>Simulates a left mouse-button release. Must follow {@link #FORM_OnLButtonDown} to
     * complete a click on a form widget.
     */
    public static final MethodHandle FORM_OnLButtonUp = downcall("FORM_OnLButtonUp",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE));

    /**
     * {@code FORM_ForceToKillFocus(FPDF_FORMHANDLE hHandle) -> FPDF_BOOL}.
     *
     * <p>Commits the value of the currently focused field and removes focus.
     * Call this after finishing all fills on a page to ensure changes are flushed.
     */
    public static final MethodHandle FORM_ForceToKillFocus = downcall("FORM_ForceToKillFocus",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
}
