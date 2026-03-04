package stirling.software.jpdfium.transform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import stirling.software.jpdfium.PdfDocument;

/**
 * Unit tests for {@link PageOps}.
 * Compile-verification tests ensuring API surface stability. Actual PDF operations require
 * native PDFium or the stub library (tested via jpdfium-document integration tests).
 */
class PageOpsTest {

    @Test
    void classIsNotInstantiable() {
        // Verifies that the PageOps utility class cannot be instantiated.
        var constructors = PageOps.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructors[0].getModifiers()),
                "PageOps constructor should be private");
    }

    @Test
    void hasExpectedMethods() throws NoSuchMethodException {
        assertNotNull(PageOps.class.getMethod("flatten",
                PdfDocument.class, int.class));
        assertNotNull(PageOps.class.getMethod("flattenAll",
                PdfDocument.class));
        assertNotNull(PageOps.class.getMethod("convertToImage",
                PdfDocument.class, int.class, int.class));
        assertNotNull(PageOps.class.getMethod("convertAllToImages",
                PdfDocument.class, int.class));
        assertNotNull(PageOps.class.getMethod("renderPage",
                PdfDocument.class, int.class, int.class));
        assertNotNull(PageOps.class.getMethod("renderAll",
                PdfDocument.class, int.class));
    }
}
