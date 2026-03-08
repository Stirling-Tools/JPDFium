package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.model.Rect;
import java.util.Optional;

/**
 * All five page boxes for a PDF page.
 */
public record PageBoxes(
        Rect mediaBox,
        Optional<Rect> cropBox,
        Optional<Rect> bleedBox,
        Optional<Rect> trimBox,
        Optional<Rect> artBox
) {}
