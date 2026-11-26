package stirling.software.jpdfium.instance;

import java.util.function.Function;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.ExtractedImage;
import stirling.software.jpdfium.doc.PdfImageExtractor;

public class ImagesTask implements Function<Object, Object> {

    @Override
    public Object apply(Object input) {
        long hash = 17;
        try (PdfDocument doc = PdfDocument.open((byte[]) input)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    for (ExtractedImage image :
                            PdfImageExtractor.extract(page.rawDocHandle(), page.rawHandle(), i)) {
                        hash = hash * 31 + image.width() * 7919L + image.height();
                    }
                }
            }
        }
        return hash;
    }
}
