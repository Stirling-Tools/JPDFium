/**
 * JPDFium - Java 25 FFM bindings for Google PDFium.
 *
 * <p>Provides PDF rendering, text extraction, and true content-stripping
 * redaction powered by PDFium via the Foreign Function &amp; Memory API.
 *
 * <p>Requires {@code --enable-native-access=stirling.software.jpdfium}
 * (or {@code --enable-native-access=ALL-UNNAMED} when running from the classpath).
 */
module stirling.software.jpdfium {
    // Public API packages
    exports stirling.software.jpdfium;
    exports stirling.software.jpdfium.exception;
    exports stirling.software.jpdfium.fonts;
    exports stirling.software.jpdfium.model;
    exports stirling.software.jpdfium.redact;
    exports stirling.software.jpdfium.redact.pii;
    exports stirling.software.jpdfium.text;
    exports stirling.software.jpdfium.text.edit;
    exports stirling.software.jpdfium.transform;

    // Internal: FFM bindings - exported for Spring Boot and advanced consumers
    exports stirling.software.jpdfium.panama;

    // Standard library requirements
    requires java.desktop;      // java.awt.image.BufferedImage
}
