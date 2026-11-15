package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.exception.PdfPasswordException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfFontEmbedderRoundTripTest {

    private static final List<COSName> PROGRAM_KEYS =
            List.of(COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3);

    static Stream<Path> pdfs() throws Exception {
        String corpus = System.getProperty("jpdfium.test.fontCorpus");
        Path root = corpus != null ? Path.of(corpus)
                : Path.of(PdfFontEmbedderRoundTripTest.class.getResource("/pdfs").toURI());
        return Files.walk(root).filter(p -> p.toString().toLowerCase().endsWith(".pdf")).sorted();
    }

    @ParameterizedTest
    @MethodSource("pdfs")
    void reembeddingStrippedProgramsRendersIdentically(Path pdf) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native font embedding unavailable");
        byte[] reference;
        byte[] stripped;
        Map<String, byte[]> programs = new LinkedHashMap<>();
        try (PDDocument original = load(pdf)) {
            assumeFalse(original.getDocument().getObjectsByType(COSName.FONT).isEmpty(), "no fonts");
            reference = save(original);
        }
        try (PDDocument doc = Loader.loadPDF(reference)) {
            Map<String, List<COSDictionary>> descriptors = new LinkedHashMap<>();
            Set<String> ambiguous = new HashSet<>();
            for (COSObject object : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(object.getObject() instanceof COSDictionary font)) continue;
                String name = font.getNameAsString(COSName.BASE_FONT);
                COSDictionary target = font;
                if (font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray kids
                        && kids.size() > 0 && kids.getObject(0) instanceof COSDictionary kid) {
                    target = kid;
                }
                COSDictionary descriptor = target.getCOSDictionary(COSName.FONT_DESC);
                if (name == null || name.indexOf('\0') >= 0) continue;
                if (descriptor == null || PROGRAM_KEYS.stream().noneMatch(descriptor::containsKey)) {
                    ambiguous.add(name);
                    continue;
                }
                for (COSName key : PROGRAM_KEYS) {
                    if (!(descriptor.getDictionaryObject(key) instanceof COSStream stream)) continue;
                    byte[] data;
                    try (var in = stream.createInputStream()) {
                        data = in.readAllBytes();
                    } catch (Exception unreadable) {
                        ambiguous.add(name);
                        continue;
                    }
                    if (data.length == 0) ambiguous.add(name);
                    if (key.equals(COSName.FONT_FILE2) && data.length >= 4
                            && data[0] == 'O' && data[1] == 'T' && data[2] == 'T' && data[3] == 'O') {
                        ambiguous.add(name);
                        continue;
                    }
                    byte[] previous = programs.putIfAbsent(name, data);
                    if (previous != null && !Arrays.equals(previous, data)) ambiguous.add(name);
                    descriptors.computeIfAbsent(name, n -> new ArrayList<>()).add(descriptor);
                }
            }
            ambiguous.forEach(programs::remove);
            assumeFalse(programs.isEmpty(), "no unambiguous embedded font program");
            programs = usable(reference, programs);
            programs.keySet().forEach(n -> descriptors.get(n).forEach(d -> PROGRAM_KEYS.forEach(d::removeItem)));
            stripped = save(doc);
        }

        byte[] output = PdfFontEmbedder.embed(stripped, programs);
        try (PdfDocument expected = PdfDocument.open(reference); PdfDocument actual = PdfDocument.open(output)) {
            assertEquals(expected.pageCount(), actual.pageCount());
            for (int i = 0; i < Math.min(expected.pageCount(), 8); i++) {
                try (PdfPage a = expected.page(i); PdfPage b = actual.page(i)) {
                    BufferedImage before = renderable(a);
                    if (before == null) continue;
                    long diff = differingPixels(before, b.renderImage(72));
                    assertEquals(0L, diff,
                            pdf.getFileName() + " page " + i + " renders differently after re-embedding: "
                                    + diff + " pixels");
                }
            }
            for (var font : PdfFontAuditor.auditAll(actual).nonEmbeddedFonts()) {
                assertTrue(!programs.containsKey(font.baseName()), font.baseName() + " was not re-embedded");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("pdfs")
    void embeddingMissingFontsKeepsTextAndLayout(Path pdf) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native font embedding unavailable");
        byte[] input = Files.readAllBytes(pdf);
        assumeTrue(input.length > 0, "empty file");
        PdfFontEmbedder.Result result;
        try {
            result = PdfFontEmbedder.embedMissing(input);
        } catch (JPDFiumException e) {
            assumeFalse(e instanceof PdfPasswordException || e instanceof PdfCorruptException
                    || e.getMessage().contains("signed") || e.getMessage().contains("password")
                    || e.getMessage().contains("rc=-1") || e.getMessage().contains("pageOpen")
                    || e.getMessage().contains("docOpen") || e.getMessage().contains("-99")
                    || e.getMessage().contains("audit is incomplete"), e.getMessage());
            throw e;
        }
        assumeFalse(result.embedded().isEmpty(), "nothing to embed");
        assertEmbeddedStylesMatch(result.pdf(), result.embedded());
        long beforeToUnicode;
        try {
            beforeToUnicode = fontsWithToUnicode(input);
        } catch (IOException e) {
            assumeTrue(false, "original input is unreadable by PDFBox: " + e.getMessage());
            return;
        }
        boolean gainedToUnicode = fontsWithToUnicode(result.pdf()) > beforeToUnicode;
        PdfDocument before;
        try {
            before = PdfDocument.open(input);
        } catch (PdfCorruptException | PdfPasswordException corrupt) {
            assumeTrue(false, "original input is unreadable by PDFium: " + corrupt.getMessage());
            return;
        }
        try (before; PdfDocument after = PdfDocument.open(result.pdf())) {
            assumeTrue(before.pageCount() == after.pageCount(), "page tree was normalized during repair");
            try {
                assertEquals(result.unresolved(), PdfFontAuditor.auditAll(after).nonEmbeddedFonts().stream()
                        .map(PdfFontAuditor.FontInfo::baseName).distinct().toList());
            } catch (JPDFiumException auditIncomplete) {
                assumeFalse(auditIncomplete.getMessage().contains("audit is incomplete"), auditIncomplete.getMessage());
                throw auditIncomplete;
            }
            long changed = 0;
            long total = 0;
            for (int i = 0; i < Math.min(before.pageCount(), 8); i++) {
                PdfPage a;
                try {
                    a = before.page(i);
                } catch (JPDFiumException unopenable) {
                    continue;
                }
                try (a; PdfPage b = after.page(i)) {
                    if (!gainedToUnicode) {
                        assertSameOrResolvedText(a.extractText(), b.extractText(), pdf.getFileName() + " page " + i);
                    } else {
                        String txt = b.extractText();
                        assertFalse(txt.contains("\uFFFD"), pdf.getFileName() + " page " + i + " text contains U+FFFD after gaining ToUnicode");
                    }
                    BufferedImage ia = renderable(a);
                    if (ia == null) continue;
                    changed += differingPixels(ia, b.renderImage(72));
                    total += (long) ia.getWidth() * ia.getHeight();
                }
            }
            System.out.printf("embedMissing %s embedded=%s unresolved=%s changedPixels=%.3f%%%n",
                    pdf.getFileName(), result.embedded(), result.unresolved(), 100.0 * changed / total);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/usr/share/fonts/type1/gsfonts", "/usr/share/ghostscript/fonts",
            "/opt/homebrew/share/ghostscript/fonts"})
    void standardFontsGainDescriptorsAndTheirMetrics(String fontDir) throws Exception {
        assumeTrue(PdfFontEmbedder.isAvailable(), "native font embedding unavailable");
        assumeTrue(fontDir.isEmpty() || Files.isDirectory(Path.of(fontDir)), "no " + fontDir);
        byte[] pdf;
        List<Standard14Fonts.FontName> names = Arrays.asList(Standard14Fonts.FontName.values());
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                float y = 760;
                for (var name : names) {
                    content.beginText();
                    content.setFont(new PDType1Font(name), 11);
                    content.newLineAtOffset(40, y -= 40);
                    content.showText(name == Standard14Fonts.FontName.SYMBOL ? "\u03b1\u03b2\u03b3\u03b4\u03c0\u2211\u221e" : name == Standard14Fonts.FontName.ZAPF_DINGBATS ? "\u2701\u2702\u2708\u2766" : "Hamburgefonstiv \u00e9\u00fc\u2013\u201c\u20ac");
                    content.endText();
                }
            }
            pdf = save(doc);
        }

        PdfFontEmbedder.Result result = fontDir.isEmpty()
                ? PdfFontEmbedder.embedMissing(pdf) : PdfFontEmbedder.embedMissing(pdf, Path.of(fontDir));
        assumeFalse(result.embedded().isEmpty(), "no standard-14 substitutes installed on this machine");
        int verified = 0;
        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            for (COSObject object : doc.getDocument().getObjectsByType(COSName.FONT)) {
                COSDictionary font = (COSDictionary) object.getObject();
                String name = font.getNameAsString(COSName.BASE_FONT).replaceFirst("^[A-Z]{6}\\+", "");
                if (!result.embedded().contains(name)) continue;
                verified++;
                COSDictionary descriptor = font.getCOSDictionary(COSName.FONT_DESC);
                assertTrue(PROGRAM_KEYS.stream().anyMatch(descriptor::containsKey), name);
                COSArray widths = font.getCOSArray(COSName.WIDTHS);
                PDType1Font afm = new PDType1Font(Standard14Fonts.getMappedFontName(name));
                int checked = 0;
                for (int code = 32; code < 127; code++) {
                    String glyph = afm.getEncoding().getName(code);
                    if (".notdef".equals(glyph)) continue;
                    float expected = Standard14Fonts.getAFM(afm.getName()).getCharacterWidth(glyph);
                    if (expected <= 0) continue;
                    float actual = ((org.apache.pdfbox.cos.COSNumber) widths.getObject(code)).floatValue();
                    assertEquals(expected, actual, 2.0f, name + " width of code " + code);
                    checked++;
                }
                assertTrue(checked > 50, name + " compared " + checked + " widths");
            }
        }
        assertEquals(result.embedded().size(), verified, "every embedded standard font was checked");
        try (PdfDocument doc = PdfDocument.open(result.pdf())) {
            List<String> left = PdfFontAuditor.audit(doc).nonEmbeddedFonts().stream()
                    .map(PdfFontAuditor.FontInfo::baseName).sorted().toList();
            assertEquals(result.unresolved().stream().sorted().toList(), left);
        }
    }

    private static Map<String, byte[]> usable(byte[] pdf, Map<String, byte[]> programs) {
        try {
            PdfFontEmbedder.embed(pdf, programs);
            return programs;
        } catch (JPDFiumException e) {
            assumeFalse(e.getMessage().contains("signed") || e.getMessage().contains("password"), e.getMessage());
            if (!e.getMessage().contains("unusable")) throw e;
        }
        Map<String, byte[]> usable = new LinkedHashMap<>(programs);
        for (var entry : programs.entrySet()) {
            try {
                PdfFontEmbedder.embed(pdf, Map.of(entry.getKey(), entry.getValue()));
            } catch (JPDFiumException e) {
                if (e.getMessage().contains("unusable")) usable.remove(entry.getKey());
            }
        }
        assumeFalse(usable.isEmpty(), "every embedded program is unusable");
        return usable;
    }

    private static PDDocument load(Path pdf) throws java.io.IOException {
        PDDocument doc;
        try {
            doc = Loader.loadPDF(pdf.toFile());
        } catch (java.io.IOException | RuntimeException e) {
            assumeTrue(false, "PDFBox cannot load: " + e.getMessage());
            return null;
        }
        if (doc.isEncrypted()) {
            doc.close();
            assumeTrue(false, "encrypted");
        }
        return doc;
    }

    private static byte[] save(PDDocument doc) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION);
        return out.toByteArray();
    }

    private static void assertEmbeddedStylesMatch(byte[] pdf, java.util.Collection<String> embeddedNames) throws java.io.IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (COSObject object : doc.getDocument().getObjectsByType(COSName.FONT)) {
                if (!(object.getObject() instanceof COSDictionary font)) continue;
                COSDictionary target = font.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray kids
                        && kids.getObject(0) instanceof COSDictionary kid ? kid : font;
                COSDictionary descriptor = target.getCOSDictionary(COSName.FONT_DESC);
                COSStream program = descriptor == null ? null : descriptor.getCOSStream(COSName.FONT_FILE2);
                String name = target.getNameAsString(COSName.BASE_FONT);
                if (program == null || name == null) continue;
                String baseName = name.replaceFirst("^[A-Z]{6}\\+", "");
                if (!embeddedNames.contains(name) && !embeddedNames.contains(baseName)) continue;
                try (var in = program.createInputStream()) {
                    var ttf = new org.apache.fontbox.ttf.TTFParser().parse(
                            new org.apache.pdfbox.io.RandomAccessReadBuffer(in.readAllBytes()));
                    if (ttf.getName() != null) {
                        String expectedStyle = style(name);
                        String actualStyle = style(ttf.getName());
                        if (!expectedStyle.isEmpty() && actualStyle.isEmpty()) {
                            continue;
                        }
                        assertEquals(expectedStyle, actualStyle, name + " embedded as " + ttf.getName());
                    }
                } catch (java.io.IOException unparsableByPdfBox) {
                }
            }
        }
    }

    private static long fontsWithToUnicode(byte[] pdf) throws java.io.IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getDocument().getObjectsByType(COSName.FONT).stream()
                    .map(COSObject::getObject)
                    .filter(o -> o instanceof COSDictionary d && d.containsKey(COSName.TO_UNICODE))
                    .count();
        }
    }

    private static void assertSameOrResolvedText(String before, String after, String where) {
        String a = before.replaceAll("\\s", "");
        String b = after.replaceAll("\\s", "");
        // Text PDFium could not resolve before embedding may become readable
        // afterwards. Everything else must round-trip exactly, position by
        // position; a replacement is only acceptable where the original text
        // already carried the unresolved marker.
        boolean unresolved = a.indexOf('?') >= 0 || a.indexOf('\uFFFD') >= 0;
        if (!unresolved) {
            assertEquals(a, b, where + " text");
            return;
        }
        assertEquals(a.length(), b.length(), where + " text length");
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            assertTrue(c == b.charAt(i) || c == '?' || c == '\uFFFD',
                    where + " text differs at " + i + ": " + a + " vs " + b);
        }
    }

        private static String style(String name) {
        String lower = name.replaceFirst("^[A-Z]{6}\\+", "").toLowerCase(java.util.Locale.ROOT);
        return (lower.matches(".*(bold|black|heavy|negrita|negreta|fett|grassetto|gras).*") ? "bold" : "")
                + (lower.matches(".*(italic|oblique|cursiva|kursiv|corsivo|italique|cursiva).*") ? "italic" : "");
    }

    private static BufferedImage renderable(PdfPage page) {
        try {
            return page.renderImage(72);
        } catch (JPDFiumException unrenderable) {
            return null;
        }
    }

    private static long differingPixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Long.MAX_VALUE;
        int[] pa = a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth());
        int[] pb = b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
        long diff = 0;
        for (int i = 0; i < pa.length; i++) if (pa[i] != pb[i]) diff++;
        return diff;
    }
}
