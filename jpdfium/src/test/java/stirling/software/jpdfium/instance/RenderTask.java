package stirling.software.jpdfium.instance;

import java.util.Arrays;
import java.util.function.Function;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

public class RenderTask implements Function<Object, Object> {

    @Override
    public Object apply(Object input) {
        long hash = 17;
        try (PdfDocument doc = PdfDocument.open((byte[]) input)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    hash = hash * 31 + Arrays.hashCode(page.renderAt(50).toFrame());
                }
            }
        }
        return hash;
    }
}
