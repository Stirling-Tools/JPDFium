import java.io.IOException;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: Main <input.pdf> <output.png>");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        try (var document = PdfDocument.open(input);
             var page = document.page(0)) {
            page.renderTo(output, 150);
        }
        System.out.println("Rendered " + input + " to " + output.toAbsolutePath());
    }
}
