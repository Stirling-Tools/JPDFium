package stirling.software.jpdfium;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.Symbols;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit test for native symbol resolution mode.
 *
 * <p>Logs whether tests are executing against the real native PDFium library
 * (FULL mode) or the stub bridge (STUB mode). In FULL mode, asserts that every
 * expected symbol is resolved with zero missing symbols.
 */
@DisplayName("Native Mode & Symbol Audit")
class NativeModeAuditTest {

    @BeforeAll
    static void reportMode() {
        System.out.println("==================================================");
        System.out.println("  JPDFium Test Environment Native Mode: " + NativeRuntime.mode());
        System.out.println("  Is Stub: " + NativeRuntime.isStub());
        System.out.println("  Is Full: " + NativeRuntime.isFull());
        System.out.println("==================================================");
    }

    @Test
    @DisplayName("All binding classes load without missing symbols in FULL mode")
    void allExpectedSymbolsResolveInFullMode() throws Exception {
        // Touch all binding and helper classes in stirling.software.jpdfium.panama
        // to ensure static initializers trigger downcalls for every native symbol.
        Class<?>[] bindingClasses = {
            stirling.software.jpdfium.panama.ActionBindings.class,
            stirling.software.jpdfium.panama.AnnotationBindings.class,
            stirling.software.jpdfium.panama.AttachmentBindings.class,
            stirling.software.jpdfium.panama.BookmarkBindings.class,
            stirling.software.jpdfium.panama.DocBindings.class,
            stirling.software.jpdfium.panama.EmbedPdfAnnotationBindings.class,
            stirling.software.jpdfium.panama.EmbedPdfDocumentBindings.class,
            stirling.software.jpdfium.panama.FlashTextLib.class,
            stirling.software.jpdfium.panama.FontLib.class,
            stirling.software.jpdfium.panama.FormFillBindings.class,
            stirling.software.jpdfium.panama.GlyphLib.class,
            stirling.software.jpdfium.panama.IcuLib.class,
            stirling.software.jpdfium.panama.ImageObjBindings.class,
            stirling.software.jpdfium.panama.JavaScriptBindings.class,
            stirling.software.jpdfium.panama.JpdfiumLib.class,
            stirling.software.jpdfium.panama.LinkBindings.class,
            stirling.software.jpdfium.panama.PageEditBindings.class,
            stirling.software.jpdfium.panama.PageImportBindings.class,
            stirling.software.jpdfium.panama.Pcre2Lib.class,
            stirling.software.jpdfium.panama.RenderBindings.class,
            stirling.software.jpdfium.panama.RepairLib.class,
            stirling.software.jpdfium.panama.RustBridgeBindings.class,
            stirling.software.jpdfium.panama.SignatureBindings.class,
            stirling.software.jpdfium.panama.StructureBindings.class,
            stirling.software.jpdfium.panama.TextPageBindings.class,
            stirling.software.jpdfium.panama.ThumbnailBindings.class,
            stirling.software.jpdfium.panama.WebLinkBindings.class,
            stirling.software.jpdfium.panama.XmpLib.class
        };
        for (Class<?> clazz : bindingClasses) {
            Class.forName(clazz.getName());
        }

        if (NativeRuntime.isFull()) {
            List<String> missing = Symbols.auditMissing();
            assertTrue(missing.isEmpty(),
                "In FULL native mode, all expected symbols must resolve. Missing: " + missing);
        } else {
            System.out.println("  Skipped FULL-mode symbol audit (currently running against STUB bridge)");
        }
    }

    @Test
    @DisplayName("NativeRuntime mode is non-null and deterministic")
    void nativeModeIsDeterministic() {
        assertNotNull(NativeRuntime.mode(), "NativeRuntime.mode() must not be null");
        assertEquals(NativeRuntime.mode(), NativeRuntime.mode(), "NativeRuntime.mode() must be stable");
    }
}
