package stirling.software.jpdfium.instance;

import java.util.function.Function;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

public class TextTask implements Function<Object, Object> {

    @Override
    public Object apply(Object input) {
        StringBuilder text = new StringBuilder();
        try (PdfDocument doc = PdfDocument.open((byte[]) input)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    text.append(page.extractText()).append('\f');
                }
            }
        }
        return text.toString();
    }
}
