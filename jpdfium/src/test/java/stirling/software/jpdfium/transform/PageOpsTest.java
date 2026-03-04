package stirling.software.jpdfium.transform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
                stirling.software.jpdfium.PdfDocument.class, int.class));
        assertNotNull(PageOps.class.getMethod("flattenAll",
                stirling.software.jpdfium.PdfDocument.class));
        assertNotNull(PageOps.class.getMethod("convertToImage",
                stirling.software.jpdfium.PdfDocument.class, int.class, int.class));
        assertNotNull(PageOps.class.getMethod("convertAllToImages",
                stirling.software.jpdfium.PdfDocument.class, int.class));
        assertNotNull(PageOps.class.getMethod("renderPage",
                stirling.software.jpdfium.PdfDocument.class, int.class, int.class));
        assertNotNull(PageOps.class.getMethod("renderAll",
                stirling.software.jpdfium.PdfDocument.class, int.class));
    }
}
