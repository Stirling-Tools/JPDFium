import stirling.software.jpdfium.spi.ImageCodec;
/**
 * JPDFium - PDF rendering, editing, and redaction for Java.
 *
 * <p>Runs PDFium, the rendering engine in Chromium, through Java's Foreign
 * Function &amp; Memory API. The native library ships prebuilt for Linux, macOS,
 * and Windows, so applications never compile or manage the engine themselves.
 *
 * <p>Enable native access at runtime with
 * {@code --enable-native-access=stirling.software.jpdfium} on the module path,
 * or {@code --enable-native-access=ALL-UNNAMED} on the classpath.
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
    exports stirling.software.jpdfium.transform;

    // Internal: FFM bindings and advanced PDF API - exported for Spring Boot and advanced consumers
    exports stirling.software.jpdfium.panama;
    exports stirling.software.jpdfium.doc;
    // Returned by public panama API (renderPageView) - exported so consumers can use the result
    exports stirling.software.jpdfium.internal;
    // Image codec SPI: jpdfium-vips registers a libvips-backed implementation
    exports stirling.software.jpdfium.spi;

    uses ImageCodec;

    // Standard library requirements
    requires transitive java.desktop;      // java.awt.image.BufferedImage surfaces in the public API
    requires java.net.http;     // java.net.http.HttpClient for link validation
}
