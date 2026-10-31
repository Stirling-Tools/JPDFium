package stirling.software.jpdfium;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Merge/split must treat forms, bookmarks and annotations the same on the
 * file-backed and bytes paths, and keep the document-level data a qpdf merge
 * or extract used to drop: form fields, page labels, named destinations,
 * viewer preferences and the document language.
 */
class PdfMergeSplitPreservationTest {

    private static final StorageOptions FILE_ONLY = StorageOptions.builder().file().build();
    private static final StorageOptions MEMORY_ONLY = StorageOptions.builder().memory().build();

    private static Path fixture(String name) {
        return Path.of("src/test/resources/pdfs/general", name);
    }

    private static int formFieldCount(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            return form == null ? 0 : form.getFields().size();
        }
    }

    private static int annotCount(byte[] pdf) throws Exception {
        int total = 0;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    total += page.annotations().size();
                }
            }
        }
        return total;
    }

    @Test
    void mergeKeepsAnnotsAndMatchesFormsOnBothPaths(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        Path forms = fixture("all_form_fields.pdf");
        Path text = tmp.resolve("text.pdf");
        Files.write(text, SyntheticPdfFactory.createDiverse(2));

        Path fileOut = tmp.resolve("merged-file.pdf");
        PdfMerge.mergeFilesToFile(List.of(forms, text), fileOut);
        byte[] fileBytes = Files.readAllBytes(fileOut);

        byte[] bytesPath;
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(forms, text))) {
            bytesPath = merged.saveBytes();
        }

        int annots = annotCount(bytesPath);
        assertTrue(annots > 0, "merged output must carry annotations");
        assertEquals(annots, annotCount(fileBytes),
                "annotation count must agree between merge paths");
        assertEquals(formFieldCount(Files.readAllBytes(forms)), formFieldCount(fileBytes),
                "merged output must keep the source's form fields");
        assertEquals(formFieldCount(bytesPath), formFieldCount(fileBytes),
                "form field count must agree between merge paths");
    }

    @Test
    void splitKeepsAnnotsAndMatchesFormsOnBothPaths(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");
        Path forms = fixture("all_form_fields.pdf");

        Path fileOut = tmp.resolve("part-file.pdf");
        PdfSplit.extractPageRangeToFile(forms, 0, 0, fileOut);
        byte[] fileBytes = Files.readAllBytes(fileOut);

        byte[] bytesPath;
        try (PdfDocument doc = PdfDocument.open(forms);
             PdfDocument part = PdfSplit.extractPageRange(doc, 0, 0)) {
            bytesPath = part.saveBytes();
        }

        int annots = annotCount(bytesPath);
        assertTrue(annots > 0, "split output must carry annotations");
        assertEquals(annots, annotCount(fileBytes),
                "annotation count must agree between split paths");
        assertEquals(formFieldCount(Files.readAllBytes(forms)), formFieldCount(fileBytes),
                "the only page's part must keep every form field");
        assertEquals(formFieldCount(bytesPath), formFieldCount(fileBytes),
                "form field count must agree between split paths");
    }

    @Test
    void mergeKeepsFieldsFromEveryInputAndTheFirstInputsCatalog(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        assumeTrue(QpdfLib.isMergeSupported(), "needs in-memory qpdf merge symbol");
        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, catalogRichPdf(4, "A"));
        Files.write(b, catalogRichPdf(3, "B"));

        Path fileOut = tmp.resolve("merged.pdf");
        PdfMerge.mergeFilesToFile(List.of(a, b), fileOut, FILE_ONLY);
        byte[] bytesPath;
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(a, b), MEMORY_ONLY)) {
            bytesPath = merged.saveBytes();
        }

        for (byte[] merged : List.of(Files.readAllBytes(fileOut), bytesPath)) {
            try (PDDocument doc = Loader.loadPDF(merged)) {
                PDDocumentCatalog catalog = doc.getDocumentCatalog();
                assertEquals(7, doc.getNumberOfPages());
                assertEquals(Map.of("Name", "A value", "Name+1", "B value"),
                        fieldValues(catalog.getAcroForm()),
                        "both inputs' fields and values survive, the clashing name renamed");
                assertArrayEquals(new String[] {"i", "ii", "1", "2", "i", "ii", "1"},
                        catalog.getPageLabels().getLabelsByPageIndices(),
                        "each input's page labels restart where its pages land");
                assertEquals("en-GB", catalog.getLanguage());
                assertTrue(catalog.getViewerPreferences().displayDocTitle());
                assertNotNull(catalog.getNames().getDests().getValue("A-last"),
                        "the first input's named destinations survive");
            }
        }
    }

    @Test
    void extractKeepsOnlyTheFieldsOfItsPagesAndRenumbersLabels(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");
        assumeTrue(QpdfLib.isExtractSupported(), "needs in-memory qpdf extract symbol");
        Path source = tmp.resolve("source.pdf");
        Files.write(source, catalogRichPdf(4, "A"));

        Path withField = tmp.resolve("with-field.pdf");
        PdfSplit.extractPageRangeToFile(source, 1, 2, withField, FILE_ONLY);
        byte[] memoryPart;
        try (PdfDocument doc = PdfDocument.open(source);
             PdfDocument part = PdfSplit.extractPageRange(doc, 1, 2, MEMORY_ONLY)) {
            memoryPart = part.saveBytes();
        }
        for (byte[] part : List.of(Files.readAllBytes(withField), memoryPart)) {
            try (PDDocument doc = Loader.loadPDF(part)) {
                PDDocumentCatalog catalog = doc.getDocumentCatalog();
                assertEquals(Map.of("Name", "A value"), fieldValues(catalog.getAcroForm()));
                assertArrayEquals(new String[] {"ii", "1"}, catalog.getPageLabels().getLabelsByPageIndices());
                assertEquals("en-GB", catalog.getLanguage());
            }
        }

        Path withoutField = tmp.resolve("without-field.pdf");
        PdfSplit.extractPageRangeToFile(source, 3, 3, withoutField, FILE_ONLY);
        try (PDDocument doc = Loader.loadPDF(withoutField.toFile())) {
            assertNull(doc.getDocumentCatalog().getAcroForm(),
                    "a part with no field pages has no form");
            assertArrayEquals(new String[] {"2"},
                    doc.getDocumentCatalog().getPageLabels().getLabelsByPageIndices());
        }
    }

    private static Map<String, String> fieldValues(PDAcroForm form) {
        return form == null ? Map.of() : form.getFields().stream()
                .collect(Collectors.toMap(PDField::getFullyQualifiedName, PDField::getValueAsString));
    }

    /**
     * Pages labelled i, ii, 1, 2..., a text field named "Name" on the second
     * page, a named destination "{tag}-last", a document language and viewer
     * preferences.
     */
    private static byte[] catalogRichPdf(int pages, String tag) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            catalog.setLanguage("en-GB");
            catalog.setViewerPreferences(new PDViewerPreferences(new COSDictionary()));
            catalog.getViewerPreferences().setDisplayDocTitle(true);

            PDPageLabels labels = new PDPageLabels(doc);
            PDPageLabelRange roman = new PDPageLabelRange();
            roman.setStyle(PDPageLabelRange.STYLE_ROMAN_LOWER);
            labels.setLabelItem(0, roman);
            PDPageLabelRange decimal = new PDPageLabelRange();
            decimal.setStyle(PDPageLabelRange.STYLE_DECIMAL);
            labels.setLabelItem(2, decimal);
            catalog.setPageLabels(labels);

            PDPageFitDestination last = new PDPageFitDestination();
            last.setPage(doc.getPage(pages - 1));
            PDDestinationNameTreeNode dests = new PDDestinationNameTreeNode();
            dests.setNames(Map.<String, PDPageDestination>of(tag + "-last", last));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
            names.setDests(dests);
            catalog.setNames(names);

            PDAcroForm form = new PDAcroForm(doc);
            catalog.setAcroForm(form);
            form.setDefaultResources(new PDResources());
            form.getDefaultResources().put(COSName.getPDFName("Helv"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultAppearance("/Helv 10 Tf 0 g");
            PDTextField field = new PDTextField(form);
            field.setPartialName("Name");
            form.getFields().add(field);
            PDAnnotationWidget widget = field.getWidgets().getFirst();
            widget.setRectangle(new PDRectangle(72, 600, 200, 20));
            widget.setPage(doc.getPage(1));
            doc.getPage(1).getAnnotations().add(widget);
            field.setValue(tag + " value");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void mergeKeepsBookmarksOnFileBackedDocs(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(2));

        int fileBookmarks;
        try (PdfDocument doc1 = PdfDocument.open(a);
             PdfDocument doc2 = PdfDocument.open(b);
             PdfDocument merged = PdfMerge.merge(List.of(doc1, doc2))) {
            fileBookmarks = merged.bookmarks().size();
        }

        byte[] bytesPath;
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(a, b))) {
            bytesPath = merged.saveBytes();
        }
        int bytesBookmarks;
        try (PdfDocument check = PdfDocument.open(bytesPath)) {
            bytesBookmarks = check.bookmarks().size();
        }
        assertEquals(bytesBookmarks, fileBookmarks, "bookmark count must agree between merge paths");
    }
}
