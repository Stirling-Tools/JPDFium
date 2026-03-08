package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import java.util.List;

/**
 * A form field read from a PDF widget annotation.
 */
public record FormField(
        int pageIndex,
        String name,
        FormFieldType type,
        String value,
        String exportValue,
        boolean checked,
        boolean readOnly,
        boolean required,
        String tooltip,
        List<String> options,
        List<Integer> selectedOptionIndices,
        Rect rect
) {}
