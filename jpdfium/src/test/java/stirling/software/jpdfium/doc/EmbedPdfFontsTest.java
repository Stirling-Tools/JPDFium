package stirling.software.jpdfium.doc;

import java.io.File;
import java.nio.file.Files;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.SyntheticPdfFactory;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.EmbedPdfFontBindings;
import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Registered fonts: register by bytes, resolve identity, use in FreeText. */
class EmbedPdfFontsTest {

    private static File systemFont() {
        String[] candidates = {
                "/System/Library/Fonts/Supplemental/Arial.ttf",
                "/usr/share/fonts/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/TTF/DejaVuSans.ttf",
                "C:\\Windows\\Fonts\\arial.ttf"
        };
        for (String p : candidates) {
            File f = new File(p);
            if (f.isFile()) return f;
        }
        return null;
    }

    @Test
    void registerMemFontResolvesIdentity() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        assumeTrue(EmbedPdfFontBindings.EPDFFont_RegisterMemFont64 != null, "needs font registration symbol");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        int id = EmbedPdfFonts.registerMemFont(null, 0, -1, bytes);
        try {
            assertTrue(id != 0, "registration must return a font id");
            Optional<String> family = EmbedPdfFonts.familyName(id);
            assertTrue(family.isPresent() && !family.get().isEmpty(), "family must resolve");
            int weight = EmbedPdfFonts.weight(id);
            assertTrue(weight >= 100 && weight <= 900, "weight in 100..900, was " + weight);
            int permission = EmbedPdfFonts.embeddingPermission(id);
            assertTrue(permission >= 0 && permission <= 4, "permission in range, was " + permission);
            assertTrue(EmbedPdfFonts.authorizeEditing(id), "authorize must succeed for known id");
            assertTrue(EmbedPdfFonts.isEditingAuthorized(id), "authorized font must allow editing");
            // Slant and instancing resolve without throwing; values depend on the font.
            EmbedPdfFonts.isItalic(id);
            EmbedPdfFonts.isInstanced(id);
        } finally {
            EmbedPdfFonts.clearRegisteredFonts();
        }
    }

    @Test
    void embeddingPolicyRoundTrips() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        assumeTrue(EmbedPdfFontBindings.EPDFDoc_SetFontEmbeddingPolicy != null, "needs policy symbol");
        byte[] pdf = SyntheticPdfFactory.singlePageWithText("policy");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            EmbedPdfFonts.setEmbeddingPolicy(doc.rawHandle(), EmbedPdfFonts.POLICY_SUBSET);
            assertEquals(EmbedPdfFonts.POLICY_SUBSET, EmbedPdfFonts.embeddingPolicy(doc.rawHandle()));
            EmbedPdfFonts.setEmbeddingPolicy(doc.rawHandle(), EmbedPdfFonts.POLICY_DEFAULT);
            assertEquals(EmbedPdfFonts.POLICY_DEFAULT, EmbedPdfFonts.embeddingPolicy(doc.rawHandle()));
        }
    }

    @Test
    void fallbackOrderToleratesAddAndClear() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        assumeTrue(EmbedPdfFontBindings.EPDFFont_AddFallbackFont != null, "needs fallback symbol");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        int id = EmbedPdfFonts.registerMemFont(null, 0, -1, bytes);
        try {
            EmbedPdfFonts.addFallbackFont(id);
            EmbedPdfFonts.clearFallbackFonts();
        } finally {
            EmbedPdfFonts.clearRegisteredFonts();
        }
    }

    @Test
    void freeTextUsesRegisteredFont() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real native library");
        assumeTrue(EmbedPdfFontBindings.EPDFFont_RegisterMemFont64 != null, "needs font registration symbol");
        File ttf = systemFont();
        assumeTrue(ttf != null, "no system TTF found");
        byte[] bytes = Files.readAllBytes(ttf.toPath());

        byte[] pdf = SyntheticPdfFactory.singlePageWithText("hello");
        try (PdfDocument doc = PdfDocument.open(pdf);
             PdfPage page = doc.page(0)) {
            int id = EmbedPdfFonts.registerMemFont(null, 0, -1, bytes);
            try {
                int index = PdfAnnotations.create(
                        page.rawHandle(), AnnotationType.FREETEXT, new Rect(50, 500, 200, 40));
                EmbedPdfAnnotations.setFreeTextFont(page.rawHandle(), index, id, 12f, 0, 0, 0);
                EmbedPdfAnnotations.generateAppearance(page.rawHandle(), index);
                assertTrue(EmbedPdfAnnotations.hasAppearanceStream(page.rawHandle(), index, 0),
                        "FreeText must have an appearance after registered-font DA");
            } finally {
                EmbedPdfFonts.clearRegisteredFonts();
            }
        }
    }
}
