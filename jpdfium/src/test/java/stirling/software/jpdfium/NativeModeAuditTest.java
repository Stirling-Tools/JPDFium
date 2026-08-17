package stirling.software.jpdfium;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.*;

import java.util.List;
import java.util.Set;

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
            ActionBindings.class,
            AnnotationBindings.class,
            AttachmentBindings.class,
            BookmarkBindings.class,
            DocBindings.class,
            EmbedPdfAnnotationBindings.class,
            EmbedPdfDocumentBindings.class,
            FlashTextLib.class,
            FontLib.class,
            FormFillBindings.class,
            GlyphLib.class,
            IcuLib.class,
            ImageObjBindings.class,
            JavaScriptBindings.class,
            JpdfiumLib.class,
            LinkBindings.class,
            PageEditBindings.class,
            PageImportBindings.class,
            Pcre2Lib.class,
            RenderBindings.class,
            RepairLib.class,
            RustBridgeBindings.class,
            SignatureBindings.class,
            StructureBindings.class,
            TextPageBindings.class,
            ThumbnailBindings.class,
            WebLinkBindings.class,
            XmpLib.class
        };
        for (Class<?> clazz : bindingClasses) {
            Class.forName(clazz.getName());
        }

        if (NativeRuntime.isFull()) {
            // EPDFAnnot_SetIcon / EPDFAnnot_GetIcon are legitimately absent from
            // some PDFium builds (including the pinned prebuild); they are bound
            // via downcallOptional and callers null-check. Everything else must
            // resolve in FULL mode.
            Set<String> knownOptional = Set.of("EPDFAnnot_SetIcon", "EPDFAnnot_GetIcon");
            List<String> unexpected = Symbols.auditMissing().stream()
                    .filter(symbol -> !knownOptional.contains(symbol))
                    .toList();
            assertTrue(unexpected.isEmpty(),
                "In FULL native mode, all required symbols must resolve. Missing: " + unexpected);
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
