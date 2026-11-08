package stirling.software.jpdfium.doc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.FormFillBindings;
import stirling.software.jpdfium.panama.NativeRuntime;

/**
 * Detached, read-only AcroForm snapshot built by {@code EPDFForm_LoadModel}.
 *
 * <p>Building it walks the form/page-tree dictionaries only: no page content is
 * parsed and no object is promoted. The snapshot copies all strings and remains
 * usable after the document is closed. It becomes stale after a mutation; load
 * a new snapshot then.
 */
public final class PdfFormSnapshot {
    /**
     * One field copied out of the native form model.
     *
     * @param index         zero-based position in the form model
     * @param objectNumber  PDF object number, or 0 when unavailable
     * @param name          fully qualified field name
     * @param alternateName alternate field name, or an empty string
     * @param family        native field-family code
     * @param flags         native field flags
     * @param values        current values in model order
     */
    public record Field(
            int index,
            int objectNumber,
            String name,
            String alternateName,
            int family,
            int flags,
            List<String> values) {}

    private final int formKind;
    private final List<Field> fields;

    private PdfFormSnapshot(int formKind, List<Field> fields) {
        this.formKind = formKind;
        this.fields = List.copyOf(fields);
    }

    /** Native form-kind code reported by the form model. */
    public int formKind() { return formKind; }

    /** Immutable snapshot of the form's fields in model order. */
    public List<Field> fields() { return fields; }

    /** Build a detached snapshot of the document's AcroForm. */
    public static PdfFormSnapshot load(PdfDocument document) {
        MethodHandle load = FormFillBindings.EPDFForm_LoadModel;
        if (load == null) return new PdfFormSnapshot(0, List.of());
        MemorySegment model = MemorySegment.NULL;
        // One arena for the whole walk: every string is copied out synchronously
        // and no buffer outlives its call, so per-field arena churn is pure cost.
        Arena arena = Arena.ofConfined();
        try {
            model = (MemorySegment) load.invokeExact(document.rawHandleForRead());
            if (model.equals(MemorySegment.NULL)) return new PdfFormSnapshot(0, List.of());
            int kind = intCall0(FormFillBindings.EPDFForm_GetFormKind, model);
            int count = intCall0(FormFillBindings.EPDFForm_CountFields, model);
            List<Field> fields = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                int objectNumber = intCall1(FormFillBindings.EPDFForm_GetFieldObjNum, model, i);
                int family = intCall1(FormFillBindings.EPDFForm_GetFieldFamily, model, i);
                int flags = intCall1(FormFillBindings.EPDFForm_GetFieldFlags, model, i);
                String name = stringCall1(arena, FormFillBindings.EPDFForm_GetFieldName, model, i);
                String alternate = stringCall1(
                        arena, FormFillBindings.EPDFForm_GetFieldAlternateName, model, i);
                int valueCount = intCall1(
                        FormFillBindings.EPDFForm_CountFieldValues, model, i);
                List<String> values = new ArrayList<>(Math.max(0, valueCount));
                for (int v = 0; v < valueCount; v++) {
                    String value = stringCall2(
                            arena, FormFillBindings.EPDFForm_GetFieldValueAt, model, i, v);
                    if (!value.isEmpty()) values.add(value);
                }
                fields.add(new Field(i, objectNumber, name, alternate, family, flags,
                        List.copyOf(values)));
            }
            return new PdfFormSnapshot(kind, fields);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("form snapshot failed", t);
        } finally {
            if (!model.equals(MemorySegment.NULL) && FormFillBindings.EPDFForm_CloseModel != null) {
                try {
                    FormFillBindings.EPDFForm_CloseModel.invokeExact(model);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                }
            }
            arena.close();
        }
    }

    private static int intCall0(MethodHandle mh, MemorySegment model) {
        if (mh == null) return 0;
        try {
            return (int) mh.invokeExact(model);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return 0;
        }
    }

    private static int intCall1(MethodHandle mh, MemorySegment model, int arg) {
        if (mh == null) return 0;
        try {
            return (int) mh.invokeExact(model, arg);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return 0;
        }
    }

    private static String stringCall1(
            Arena arena, MethodHandle mh, MemorySegment model, int arg) {
        if (mh == null) return "";
        try {
            long needed = (long) mh.invokeExact(model, arg, MemorySegment.NULL, 0L);
            if (needed <= 2) return "";
            MemorySegment buf = arena.allocate(needed);
            long written = (long) mh.invokeExact(model, arg, buf, needed);
            return FfmHelper.fromWideString(buf, written);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return "";
        }
    }

    private static String stringCall2(
            Arena arena, MethodHandle mh, MemorySegment model, int first, int second) {
        if (mh == null) return "";
        try {
            long needed = (long) mh.invokeExact(
                    model, first, second, MemorySegment.NULL, 0L);
            if (needed <= 2) return "";
            MemorySegment buf = arena.allocate(needed);
            long written = (long) mh.invokeExact(model, first, second, buf, needed);
            return FfmHelper.fromWideString(buf, written);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return "";
        }
    }
}
