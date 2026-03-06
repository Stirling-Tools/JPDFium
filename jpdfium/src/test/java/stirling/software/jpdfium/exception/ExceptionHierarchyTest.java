package stirling.software.jpdfium.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the exception hierarchy (pure Java, no native dependency). */
class ExceptionHierarchyTest {

    @Test
    void jpdfiumExceptionMessage() {
        var ex = new JPDFiumException("boom");
        assertEquals("boom", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void jpdfiumExceptionWithCause() {
        var cause = new RuntimeException("root");
        var ex = new JPDFiumException("boom", cause);
        assertEquals("boom", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void jpdfiumExceptionIsRuntimeException() {
        assertInstanceOf(RuntimeException.class, new JPDFiumException("x"));
    }

    // -- PdfCorruptException --------------------------------------------

    @Test
    void pdfCorruptException() {
        var ex = new PdfCorruptException("bad format");
        assertInstanceOf(JPDFiumException.class, ex);
        assertEquals("bad format", ex.getMessage());
    }

    // -- PdfPasswordException -------------------------------------------

    @Test
    void pdfPasswordException() {
        var ex = new PdfPasswordException("needs password");
        assertInstanceOf(JPDFiumException.class, ex);
        assertEquals("needs password", ex.getMessage());
    }

    // -- NativeLoadException --------------------------------------------

    @Test
    void nativeLoadExceptionMessage() {
        var ex = new NativeLoadException("dlopen failed");
        assertInstanceOf(JPDFiumException.class, ex);
        assertEquals("dlopen failed", ex.getMessage());
    }

    @Test
    void nativeLoadExceptionWithCause() {
        var cause = new UnsatisfiedLinkError("lib not found");
        var ex = new NativeLoadException("load failed", cause);
        assertEquals("load failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    // -- NativeNotFoundException ----------------------------------------

    @Test
    void nativeNotFoundExceptionBuildsPlatformMessage() {
        var ex = new NativeNotFoundException("linux-x64");
        assertTrue(ex.getMessage().contains("linux-x64"));
        assertTrue(ex.getMessage().contains("jpdfium-natives-linux-x64"));
        assertInstanceOf(JPDFiumException.class, ex);
    }
}
