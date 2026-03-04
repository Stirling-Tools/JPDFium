package stirling.software.jpdfium.text.edit;

/**
 * Placeholder for text editing operations.
 *
 * <p>Planned features using PDFium's FPDFText edit API:
 * <ul>
 *   <li>Add text objects to pages</li>
 *   <li>Replace text (remove + add)</li>
 *   <li>Change font properties</li>
 *   <li>Add watermarks</li>
 *   <li>Add headers/footers</li>
 *   <li>Add page numbers</li>
 * </ul>
 *
 * <h3>Future Usage Example</h3>
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     TextEditor editor = TextEditor.forPage(doc, 0);
 *     editor.addText("CONFIDENTIAL", 100, 700, "Helvetica-Bold", 24, 0xFFFF0000);
 *     editor.commit();
 *     doc.save(Path.of("output.pdf"));
 * }
 * }</pre>
 */
public final class TextEditor {

    private TextEditor() {}

    // TODO: Implement text editing operations once native bindings for
    // FPDFText_LoadFont, FPDFText_SetText, FPDFPageObj_CreateTextObj etc. are added.
}
