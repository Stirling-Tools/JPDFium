package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.FormFillException;
import stirling.software.jpdfium.model.BooleanString;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.FormFillBindings;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Programmatic form filling for PDF AcroForms. Supports text fields, checkboxes,
 * radio buttons, combo boxes, and list boxes.
 *
 * <p>The filler collects all desired field values via builder methods and applies them
 * atomically in {@link #apply()}, which manages the complete PDFium form-fill lifecycle
 * (environment init -> page notifications -> value writes -> focus commit -> cleanup).
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("form.pdf"))) {
 *     FillResult result = PdfFormFiller.fill(doc)
 *         .text("firstName", "Jane")
 *         .text("lastName",  "Doe")
 *         .check("agreeToTerms")
 *         .radio("filingStatus", "married")
 *         .select("state", "Illinois")
 *         .apply();
 *
 *     System.out.println(result.summary());
 *     doc.save(Path.of("filled.pdf"));
 * }
 * }</pre>
 *
 * <h2>Fill from a map</h2>
 * <pre>{@code
 * PdfFormFiller.fill(doc)
 *     .fromMap(Map.of("name", "Jane Doe", "agree", "Yes", "state", "IL"))
 *     .applyAndSave(Path.of("filled.pdf"));
 * }</pre>
 */
public final class PdfFormFiller {

    private final PdfDocument document;

    // Type-specific fill operations (collected from builder calls)
    private final Map<String, String> textValues = new LinkedHashMap<>();
    private final Map<String, Boolean> checkboxValues = new LinkedHashMap<>();
    private final Map<String, String> radioValues = new LinkedHashMap<>();
    private final Map<String, List<String>> listLabelValues = new LinkedHashMap<>();
    private final Map<String, List<Integer>> listIndexValues = new LinkedHashMap<>();

    // Smart-map fill: type is detected at apply() time
    private final Map<String, String> mapValues = new LinkedHashMap<>();

    private boolean flattenAfterFill = false;

    private PdfFormFiller(PdfDocument document) {
        this.document = document;
    }

    /** Create a new filler for the given document. */
    public static PdfFormFiller fill(PdfDocument document) {
        return new PdfFormFiller(document);
    }

    // Text fields

    /**
     * Set a text field value. Also works for editable combo boxes.
     *
     * @param fieldName fully qualified field name as reported by {@code PdfFormReader}
     * @param value     the text to set
     */
    public PdfFormFiller text(String fieldName, String value) {
        textValues.put(fieldName, value);
        return this;
    }

    /** Set multiple text fields at once. */
    public PdfFormFiller text(Map<String, String> fields) {
        textValues.putAll(fields);
        return this;
    }

    // Checkboxes

    /** Check a checkbox (set to its "on" export value). */
    public PdfFormFiller check(String fieldName) {
        checkboxValues.put(fieldName, true);
        return this;
    }

    /** Uncheck a checkbox. */
    public PdfFormFiller uncheck(String fieldName) {
        checkboxValues.put(fieldName, false);
        return this;
    }

    /** Set checkbox to a specific state. */
    public PdfFormFiller checkbox(String fieldName, boolean checked) {
        checkboxValues.put(fieldName, checked);
        return this;
    }

    // Radio buttons

    /**
     * Select a radio button by the export value of the option to activate.
     * For example, {@code radio("gender", "male")} selects the radio button
     * whose export value is {@code "male"}.
     */
    public PdfFormFiller radio(String fieldName, String exportValue) {
        radioValues.put(fieldName, exportValue);
        return this;
    }

    // Combo and list boxes

    /**
     * Select a combo box or list box option by its visible label.
     */
    public PdfFormFiller select(String fieldName, String optionLabel) {
        listLabelValues.put(fieldName, List.of(optionLabel));
        return this;
    }

    /**
     * Select a combo box or list box option by its zero-based index.
     */
    public PdfFormFiller selectByIndex(String fieldName, int index) {
        listIndexValues.put(fieldName, List.of(index));
        return this;
    }

    /**
     * Select multiple options in a multi-select list box by label.
     */
    public PdfFormFiller selectMultiple(String fieldName, List<String> optionLabels) {
        listLabelValues.put(fieldName, List.copyOf(optionLabels));
        return this;
    }

    /**
     * Select multiple options in a multi-select list box by zero-based index.
     */
    public PdfFormFiller selectByIndices(String fieldName, List<Integer> indices) {
        listIndexValues.put(fieldName, List.copyOf(indices));
        return this;
    }

    // Smart fill from map

    /**
     * Smart-fill from a flat string map. The field type is detected at {@link #apply()} time:
     * <ul>
     *   <li>Text / editable combo: value is written directly.</li>
     *   <li>Checkbox: {@code "yes"/"true"/"1"/"on"} (case-insensitive) -> checked, else unchecked.</li>
     *   <li>Radio: value is matched against export values.</li>
     *   <li>Combo / list: value is matched against option labels (first match wins).</li>
     * </ul>
     *
     * <p>Explicit calls ({@link #text}, {@link #check}, {@link #radio}, etc.) take precedence
     * over map values when both target the same field.
     */
    public PdfFormFiller fromMap(Map<String, String> data) {
        mapValues.putAll(data);
        return this;
    }

    // Options

    /**
     * Flatten all modified pages after filling (bake field values into page content).
     * Makes those pages non-editable. Default: false.
     */
    public PdfFormFiller flatten() {
        this.flattenAfterFill = true;
        return this;
    }

    // Apply

    /**
     * Apply all accumulated fill operations to the document.
     *
     * <p>The document is modified in place. Call {@link PdfDocument#save(Path)} afterwards
     * to persist the changes.
     *
     * @return a {@link FillResult} describing which fields were filled and which were skipped
     * @throws FormFillException if the PDFium form environment cannot be initialised
     */
    public FillResult apply() {
        MemorySegment rawDoc = document.rawHandle();
        int pageCount = document.pageCount();

        List<String> filledList = new ArrayList<>();
        List<String> skippedList = new ArrayList<>();
        int[] flattenedPages = {0};

        FormEnv formEnv = initFormEnvironment(rawDoc);
        try {
            Map<String, List<RadioMember>> radioGroupMembers = new LinkedHashMap<>();

            processStandardFields(formEnv, pageCount, filledList, skippedList, flattenedPages, radioGroupMembers);
            processRadioGroups(formEnv.formHandle, radioGroupMembers, filledList, flattenedPages);
            collectSkipped(filledList, skippedList);

        } finally {
            safeSilent0(DocBindings.FPDFDOC_ExitFormFillEnvironment, formEnv.formHandle);
            formEnv.arena().close();
        }

        return new FillResult(List.copyOf(filledList), List.copyOf(skippedList), flattenedPages[0]);
    }

    private record FormEnv(MemorySegment formHandle, Arena arena) {}

    private static FormEnv initFormEnvironment(MemorySegment rawDoc) {
        Arena formArena = Arena.ofConfined();
        MemorySegment formInfo = formArena.allocate(256);
        formInfo.set(ValueLayout.JAVA_INT, 0, 1);
        MemorySegment formHandle;
        try {
            formHandle = (MemorySegment) DocBindings.FPDFDOC_InitFormFillEnvironment
                    .invokeExact(rawDoc, formInfo);
        } catch (Throwable t) {
            formArena.close();
            throw new FormFillException("Failed to initialise form fill environment", t);
        }
        if (MemorySegment.NULL.equals(formHandle)) {
            formArena.close();
            throw new FormFillException("FPDFDOC_InitFormFillEnvironment returned NULL - document may have no form");
        }
        return new FormEnv(formHandle, formArena);
    }

    private void processStandardFields(FormEnv formEnv, int pageCount,
                                        List<String> filledList, List<String> skippedList,
                                        int[] flattenedPages, Map<String, List<RadioMember>> radioGroupMembers) {
        for (int pageIdx = 0; pageIdx < pageCount; pageIdx++) {
            boolean[] pageModified = {false};
            PdfPage pdfPage = document.page(pageIdx);
            try (pdfPage) {
                MemorySegment rawPage = pdfPage.rawHandle();
                safeVoid(FormFillBindings.FORM_OnAfterLoadPage, rawPage, formEnv.formHandle);

                int annotCount = safeInt0(AnnotationBindings.FPDFPage_GetAnnotCount, rawPage);
                for (int annotIdx = 0; annotIdx < annotCount; annotIdx++) {
                    MemorySegment annot = safeSegment(AnnotationBindings.FPDFPage_GetAnnot, rawPage, annotIdx);
                    if (MemorySegment.NULL.equals(annot)) continue;
                    try {
                        processAnnotation(formEnv.formHandle, rawPage, annot, annotIdx, pageIdx,
                                filledList, pageModified, radioGroupMembers);
                    } finally {
                        safeSilent0(AnnotationBindings.FPDFPage_CloseAnnot, annot);
                    }
                }

                if (pageModified[0]) {
                    safeSilent0(FormFillBindings.FORM_ForceToKillFocus, formEnv.formHandle);
                }
                if (flattenAfterFill && pageModified[0]) {
                    safeSilentInt2(PageEditBindings.FPDFPage_Flatten, rawPage, 0);
                    flattenedPages[0]++;
                }
                safeVoid(FormFillBindings.FORM_OnBeforeClosePage, rawPage, formEnv.formHandle);

            } catch (FormFillException ffe) {
                throw ffe;
            } catch (Throwable t) {
                throw new FormFillException("Error filling page " + pageIdx, t);
            }
        }
    }

    private void processRadioGroups(MemorySegment formHandle, Map<String, List<RadioMember>> radioGroupMembers,
                                     List<String> filledList, int[] flattenedPages) {
        for (Map.Entry<String, List<RadioMember>> entry : radioGroupMembers.entrySet()) {
            String fieldName = entry.getKey();
            String selectedExportValue = getRadioValue(fieldName);
            boolean filled = fillRadioGroup(formHandle, selectedExportValue, entry.getValue(), flattenedPages);
            if (filled && !filledList.contains(fieldName)) filledList.add(fieldName);
        }
    }

    /**
     * Apply all operations and immediately save to the given path.
     */
    public FillResult applyAndSave(Path output) {
        FillResult result = apply();
        document.save(output);
        return result;
    }

    // Annotation processing

    private void processAnnotation(MemorySegment formHandle, MemorySegment rawPage,
                                    MemorySegment annot, int annotIdx, int pageIdx,
                                    List<String> filledList, boolean[] pageModified,
                                    Map<String, List<RadioMember>> radioGroupMembers) throws Throwable {
        int subtype = safeInt0(AnnotationBindings.FPDFAnnot_GetSubtype, annot);
        if (subtype != 20) return; // 20 = FPDF_ANNOT_WIDGET

        int flags = safeInt0b(AnnotationBindings.FPDFAnnot_GetFormFieldFlags, formHandle, annot);
        if ((flags & 1) != 0) return; // read-only

        String fieldName = readWideString(AnnotationBindings.FPDFAnnot_GetFormFieldName, formHandle, annot);
        int typeCode = safeInt0b(AnnotationBindings.FPDFAnnot_GetFormFieldType, formHandle, annot);
        FormFieldType ft = FormFieldType.fromCode(typeCode);

        boolean filled = switch (ft) {
            case TEXT -> handleTextField(formHandle, rawPage, annot, fieldName);
            case CHECKBOX -> handleCheckbox(formHandle, rawPage, annot, fieldName);
            case RADIO -> {
                if (hasRadioOp(fieldName)) {
                    String exportVal = readWideString(AnnotationBindings.FPDFAnnot_GetFormFieldExportValue, formHandle, annot);
                    radioGroupMembers.computeIfAbsent(fieldName, k -> new ArrayList<>())
                            .add(new RadioMember(pageIdx, annotIdx, exportVal));
                }
                yield false; // handled in phase 2
            }
            case COMBOBOX -> handleCombo(formHandle, rawPage, annot, fieldName, flags);
            case LISTBOX -> handleList(formHandle, rawPage, annot, fieldName);
            default -> false;
        };

        if (filled) {
            if (!filledList.contains(fieldName)) filledList.add(fieldName);
            pageModified[0] = true;
        }
    }

    // Field handlers

    /**
     * Fill a text field using the focus -> select-all -> replace approach.
     * This updates both /V and the appearance stream.
     */
    private boolean handleTextField(MemorySegment formHandle, MemorySegment rawPage,
                                     MemorySegment annot, String fieldName) {
        String value = resolveTextValue(fieldName);
        if (value == null) return false;
        try (Arena arena = Arena.ofConfined()) {
            // Focus the annotation
            int focused = safeInt0b(FormFillBindings.FORM_SetFocusedAnnot, formHandle, annot);
            if (focused == 0) {
                setAnnotString(arena, annot, AnnotationKeys.V, value);
                return true;
            }
            // Select all existing text
            safeSilent0b(FormFillBindings.FORM_SelectAllText, formHandle, rawPage);
            // Replace with new text (updates /V + appearance stream)
            MemorySegment wideText = FfmHelper.toWideString(arena, value);
            FormFillBindings.FORM_ReplaceSelection.invokeExact(formHandle, rawPage, wideText);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Toggle a checkbox using a simulated mouse click, with a fallback to
     * directly setting /V and /AS via SetStringValue if the click doesn't take effect.
     */
    private boolean handleCheckbox(MemorySegment formHandle, MemorySegment rawPage,
                                    MemorySegment annot, String fieldName) {
        Boolean wantChecked = resolveCheckboxValue(fieldName);
        if (wantChecked == null) return false;
        try {
            // Read the export value for this checkbox (the "on" value)
            String exportValue = readWideString(AnnotationBindings.FPDFAnnot_GetFormFieldExportValue, formHandle, annot);
            if (exportValue.isEmpty()) exportValue = "Yes"; // common default

            // Read current value
            String currentValue = readWideString(AnnotationBindings.FPDFAnnot_GetFormFieldValue, formHandle, annot);
            boolean currentlyChecked = !currentValue.isEmpty()
                    && !currentValue.equalsIgnoreCase("Off");
            if (currentlyChecked == wantChecked) return true; // already correct

            // Attempt 1: simulated mouse click at annotation centre
            double[] centre = getAnnotCentre(annot);
            if (centre != null) {
                FormFillBindings.FORM_OnLButtonDown.invokeExact(formHandle, rawPage, 0, centre[0], centre[1]);
                FormFillBindings.FORM_OnLButtonUp.invokeExact(formHandle, rawPage, 0, centre[0], centre[1]);

                // Verify click took effect
                String afterValue = readWideString(AnnotationBindings.FPDFAnnot_GetFormFieldValue, formHandle, annot);
                boolean nowChecked = !afterValue.isEmpty()
                        && !afterValue.equalsIgnoreCase("Off");
                if (nowChecked == wantChecked) return true;
            }

            // Attempt 2: fallback - directly set /V and /AS string values
            try (Arena arena = Arena.ofConfined()) {
                String targetValue = wantChecked ? exportValue : "Off";
                setAnnotString(arena, annot, AnnotationKeys.V, targetValue);
                setAnnotString(arena, annot, AnnotationKeys.AS, targetValue);
                return true;
            }
        } catch (Throwable t) { return false; }
    }

    private boolean handleCombo(MemorySegment formHandle, MemorySegment rawPage, MemorySegment annot,
                                 String fieldName, int flags) {
        boolean isEditable = (flags & (1 << 18)) != 0; // EDIT flag
        if (isEditable) {
            String value = resolveTextValue(fieldName);
            //noinspection VariableNotUsedInsideIf
            if (value != null) {
                return handleTextField(formHandle, rawPage, annot, fieldName);
            }
        }
        return handleList(formHandle, rawPage, annot, fieldName);
    }

    private boolean handleList(MemorySegment formHandle, MemorySegment rawPage,
                                MemorySegment annot, String fieldName) {
        List<Integer> indices = resolveListIndices(formHandle, annot, fieldName);
        if (indices == null || indices.isEmpty()) return false;

        // FORM_SetIndexSelected operates on the FOCUSED annotation - focus it first
        safeInt0b(FormFillBindings.FORM_SetFocusedAnnot, formHandle, annot);

        int optCount = safeInt0b(AnnotationBindings.FPDFAnnot_GetOptionCount, formHandle, annot);
        // Deselect all existing selections
        for (int i = 0; i < optCount; i++) {
            safeSilentInt4(FormFillBindings.FORM_SetIndexSelected, formHandle, rawPage, i, 0);
        }
        // Select desired indices and collect labels for /V fallback
        boolean any = false;
        List<String> selectedLabels = new ArrayList<>();
        for (int idx : indices) {
            if (idx >= 0 && idx < optCount) {
                int ok = safeInt4(FormFillBindings.FORM_SetIndexSelected, formHandle, rawPage, idx, 1);
                if (ok != 0) {
                    any = true;
                    String label = readOptionLabel(formHandle, annot, idx);
                    if (!label.isEmpty()) selectedLabels.add(label);
                }
            }
        }
        // Fallback: explicitly set /V for the first selected label so value readback works
        if (any && !selectedLabels.isEmpty()) {
            try (Arena arena = Arena.ofConfined()) {
                setAnnotString(arena, annot, AnnotationKeys.V, selectedLabels.getFirst());
            }
        }
        return any;
    }

    private boolean fillRadioGroup(MemorySegment formHandle, String selectedExportValue,
                                    List<RadioMember> members, int[] flattenedPages) {
        Map<Integer, List<RadioMember>> byPage = new LinkedHashMap<>();
        for (RadioMember m : members) {
            byPage.computeIfAbsent(m.pageIdx(), k -> new ArrayList<>()).add(m);
        }

        boolean anyFilled = false;
        for (Map.Entry<Integer, List<RadioMember>> entry : byPage.entrySet()) {
            int pageIdx = entry.getKey();
            PdfPage pdfPage = document.page(pageIdx);
            try (pdfPage) {
                MemorySegment rawPage = pdfPage.rawHandle();
                boolean pageModified = false;
                safeVoid(FormFillBindings.FORM_OnAfterLoadPage, rawPage, formHandle);
                for (RadioMember member : entry.getValue()) {
                    MemorySegment annot = safeSegment(AnnotationBindings.FPDFPage_GetAnnot, rawPage, member.annotIdx());
                    if (MemorySegment.NULL.equals(annot)) continue;
                    try {
                        boolean isTarget = selectedExportValue.equals(member.exportValue());

                        // Attempt 1: click the target radio button
                        if (isTarget) {
                            double[] centre = getAnnotCentre(annot);
                            if (centre != null) {
                                FormFillBindings.FORM_OnLButtonDown.invokeExact(formHandle, rawPage, 0, centre[0], centre[1]);
                                FormFillBindings.FORM_OnLButtonUp.invokeExact(formHandle, rawPage, 0, centre[0], centre[1]);
                            }

                            // Verify click took effect
                            String afterValue = readWideString(AnnotationBindings.FPDFAnnot_GetFormFieldValue, formHandle, annot);
                            if (selectedExportValue.equals(afterValue)) {
                                anyFilled = true;
                                pageModified = true;
                                continue;
                            }

                            // Attempt 2: fallback - set /V and /AS directly
                            try (Arena arena = Arena.ofConfined()) {
                                setAnnotString(arena, annot, AnnotationKeys.V, selectedExportValue);
                                setAnnotString(arena, annot, AnnotationKeys.AS, selectedExportValue);
                            }
                            anyFilled = true;
                            pageModified = true;
                        } else {
                            // Deselect non-target radio buttons via /AS = Off
                            try (Arena arena = Arena.ofConfined()) {
                                setAnnotString(arena, annot, AnnotationKeys.AS, "Off");
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        safeSilent0(AnnotationBindings.FPDFPage_CloseAnnot, annot);
                    }
                }
                if (pageModified) safeSilent0(FormFillBindings.FORM_ForceToKillFocus, formHandle);
                if (flattenAfterFill && pageModified) {
                    safeSilentInt2(PageEditBindings.FPDFPage_Flatten, rawPage, 0);
                    flattenedPages[0]++;
                }
                safeVoid(FormFillBindings.FORM_OnBeforeClosePage, rawPage, formHandle);
            } catch (Throwable ignored) {
            }
        }
        return anyFilled;
    }

    // Value resolution

    private String resolveTextValue(String fieldName) {
        if (textValues.containsKey(fieldName)) return textValues.get(fieldName);
        if (mapValues.containsKey(fieldName)) return mapValues.get(fieldName);
        return null;
    }

    private Boolean resolveCheckboxValue(String fieldName) {
        if (checkboxValues.containsKey(fieldName)) return checkboxValues.get(fieldName);
        if (mapValues.containsKey(fieldName)) {
            return BooleanString.parse(mapValues.get(fieldName));
        }
        return null;
    }

    private boolean hasRadioOp(String fieldName) {
        return radioValues.containsKey(fieldName) || mapValues.containsKey(fieldName);
    }

    private String getRadioValue(String fieldName) {
        if (radioValues.containsKey(fieldName)) return radioValues.get(fieldName);
        return mapValues.getOrDefault(fieldName, "");
    }

    private List<Integer> resolveListIndices(MemorySegment formHandle, MemorySegment annot, String fieldName) {
        if (listIndexValues.containsKey(fieldName)) return listIndexValues.get(fieldName);

        List<String> labels = listLabelValues.containsKey(fieldName)
                ? listLabelValues.get(fieldName)
                : mapValues.containsKey(fieldName) ? List.of(mapValues.get(fieldName)) : null;
        if (labels == null) return null;

        int optCount = safeInt0b(AnnotationBindings.FPDFAnnot_GetOptionCount, formHandle, annot);
        List<Integer> indices = new ArrayList<>();
        for (String label : labels) {
            for (int i = 0; i < optCount; i++) {
                if (label.equalsIgnoreCase(readOptionLabel(formHandle, annot, i))) {
                    indices.add(i);
                    break;
                }
            }
        }
        return indices;
    }

    private void collectSkipped(List<String> filled, List<String> skipped) {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(textValues.keySet());
        all.addAll(checkboxValues.keySet());
        all.addAll(radioValues.keySet());
        all.addAll(listLabelValues.keySet());
        all.addAll(listIndexValues.keySet());
        all.addAll(mapValues.keySet());
        for (String name : all) {
            if (!filled.contains(name)) skipped.add(name);
        }
    }

    // Low-level helpers

    /**
     * Return the page-coordinate centre of an annotation's bounding rectangle,
     * or {@code null} if the rect cannot be read.
     * <p>The returned array is {@code [cx, cy]} in PDF page coordinates (origin bottom-left).
     * FS_RECTF layout: left, top, right, bottom (all floats).
     */
    private static double[] getAnnotCentre(MemorySegment annot) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
            int ok = (int) AnnotationBindings.FPDFAnnot_GetRect.invokeExact(annot, rect);
            if (ok == 0) return null;
            float left   = rect.get(ValueLayout.JAVA_FLOAT, 0);
            float top    = rect.get(ValueLayout.JAVA_FLOAT, 4);
            float right  = rect.get(ValueLayout.JAVA_FLOAT, 8);
            float bottom = rect.get(ValueLayout.JAVA_FLOAT, 12);
            return new double[]{(left + right) / 2.0, (top + bottom) / 2.0};
        } catch (Throwable t) { return null; }
    }

    static String readWideString(java.lang.invoke.MethodHandle mh, MemorySegment arg1, MemorySegment arg2) {
        try (Arena arena = Arena.ofConfined()) {
            long needed = (long) mh.invokeExact(arg1, arg2, MemorySegment.NULL, 0L);
            if (needed <= 2) return "";
            MemorySegment buf = arena.allocate(needed);
            @SuppressWarnings("unused")
            long written = (long) mh.invokeExact(arg1, arg2, buf, needed);
            return FfmHelper.fromWideString(buf, needed);
        } catch (Throwable t) { return ""; }
    }

    private static String readOptionLabel(MemorySegment formHandle, MemorySegment annot, int index) {
        try (Arena arena = Arena.ofConfined()) {
            long needed = (long) AnnotationBindings.FPDFAnnot_GetOptionLabel
                    .invokeExact(formHandle, annot, index, MemorySegment.NULL, 0L);
            if (needed <= 2) return "";
            MemorySegment buf = arena.allocate(needed);
            @SuppressWarnings("unused")
            long written = (long) AnnotationBindings.FPDFAnnot_GetOptionLabel.invokeExact(formHandle, annot, index, buf, needed);
            return FfmHelper.fromWideString(buf, needed);
        } catch (Throwable t) { return ""; }
    }

    static void setAnnotString(Arena arena, MemorySegment annot, String key, String value) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            MemorySegment keyBuf = arena.allocate(keyBytes.length + 1L);
            MemorySegment.copy(keyBytes, 0, keyBuf, ValueLayout.JAVA_BYTE, 0, keyBytes.length);
            keyBuf.set(ValueLayout.JAVA_BYTE, keyBytes.length, (byte) 0);
            MemorySegment valueSeg = FfmHelper.toWideString(arena, value);
            @SuppressWarnings("unused")
            int ok = (int) AnnotationBindings.FPDFAnnot_SetStringValue.invokeExact(annot, keyBuf, valueSeg);
        } catch (Throwable ignored) {}
    }

    // Typed invokers

    /** Invoke void(ADDRESS, ADDRESS) - throws FormFillException on error. */
    private static void safeVoid(java.lang.invoke.MethodHandle mh, MemorySegment a, MemorySegment b) {
        try { mh.invokeExact(a, b); }
        catch (Throwable t) { throw new FormFillException("FFM call failed", t); }
    }

    /** Invoke void(ADDRESS) silently. */
    private static void safeSilent0(java.lang.invoke.MethodHandle mh, MemorySegment a) {
        try { mh.invokeExact(a); } catch (Throwable ignored) {}
    }

    /** Invoke void(ADDRESS, ADDRESS) silently. */
    @SuppressWarnings("unused")
    private static void safeSilent0b(java.lang.invoke.MethodHandle mh, MemorySegment a, MemorySegment b) {
        try { mh.invokeExact(a, b); } catch (Throwable ignored) {}
    }

    /** Invoke int(ADDRESS) -> 0 on error. */
    private static int safeInt0(java.lang.invoke.MethodHandle mh, MemorySegment a) {
        try { return (int) mh.invokeExact(a); } catch (Throwable t) { return 0; }
    }

    /** Invoke int(ADDRESS, ADDRESS) -> 0 on error. */
    private static int safeInt0b(java.lang.invoke.MethodHandle mh, MemorySegment a, MemorySegment b) {
        try { return (int) mh.invokeExact(a, b); } catch (Throwable t) { return 0; }
    }

    /** Invoke int(ADDRESS, int) -> 0 on error (e.g. FPDFPage_Flatten). */
    private static void safeSilentInt2(java.lang.invoke.MethodHandle mh, MemorySegment a, int b) {
        try { mh.invokeExact(a, b); } catch (Throwable ignored) {}
    }

    /** Invoke int(ADDRESS, ADDRESS, int, int) -> 0 on error. */
    private static int safeInt4(java.lang.invoke.MethodHandle mh, MemorySegment a, MemorySegment b, int c, int d) {
        try { return (int) mh.invokeExact(a, b, c, d); } catch (Throwable t) { return 0; }
    }

    /** Invoke void(ADDRESS, ADDRESS, int, int) silently. */
    private static void safeSilentInt4(java.lang.invoke.MethodHandle mh, MemorySegment a, MemorySegment b, int c, int d) {
        try { mh.invokeExact(a, b, c, d); } catch (Throwable ignored) {}
    }

    /** Invoke ADDRESS(ADDRESS, int) -> NULL on error. */
    private static MemorySegment safeSegment(java.lang.invoke.MethodHandle mh, MemorySegment a, int b) {
        try { return (MemorySegment) mh.invokeExact(a, b); } catch (Throwable t) { return MemorySegment.NULL; }
    }

    // Internal records

    /** Identifies a radio button annotation by page + annotation index + export value. */
    private record RadioMember(int pageIdx, int annotIdx, String exportValue) {}
}
