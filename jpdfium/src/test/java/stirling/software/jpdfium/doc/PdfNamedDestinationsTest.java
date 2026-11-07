package stirling.software.jpdfium.doc;

import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.junit.jupiter.api.Test;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfNamedDestinationsTest {

    @Test
    void listDecodesNamesLargerThanTheScratchBuffer() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "named destinations require real PDFium natives");
        // Longer than the FFM scratch buffer, so FPDF_GetNamedDest reports -1 on
        // the first probe and the Java retry path has to be used.
        String longName = "dest-" + "x".repeat(3000);
        byte[] pdf = pdfWithDestinations(List.of("short", longName));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<NamedDestination> dests = PdfNamedDestinations.list(doc.rawHandle());
            assertEquals(2, dests.size(), "both named destinations must survive");
            List<String> names = dests.stream().map(NamedDestination::name).collect(Collectors.toList());
            assertTrue(names.contains("short"), "short name must decode: " + names);
            assertTrue(names.contains(longName), "long name must decode, not be dropped silently");
        }
    }

    @Test
    void listDecodesNamesInsideTheScratchBuffer() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "named destinations require real PDFium natives");
        byte[] pdf = pdfWithDestinations(List.of("alpha", "beta"));

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            List<NamedDestination> dests = PdfNamedDestinations.list(doc.rawHandle());
            assertEquals(2, dests.size());
            List<String> names = dests.stream().map(NamedDestination::name).collect(Collectors.toList());
            assertTrue(names.contains("alpha"), "alpha must decode: " + names);
            assertTrue(names.contains("beta"), "beta must decode: " + names);
        }
    }

    private static byte[] pdfWithDestinations(List<String> names) throws Exception {
        try (PDDocument pd = new PDDocument()) {
            pd.addPage(new PDPage());
            TreeMap<String, PDPageDestination> map = new TreeMap<>();
            for (String name : names) {
                PDPageXYZDestination dest = new PDPageXYZDestination();
                dest.setPage(pd.getPage(0));
                map.put(name, dest);
            }
            PDDestinationNameTreeNode tree = new PDDestinationNameTreeNode();
            tree.setNames(map);
            PDDocumentNameDictionary catalogNames = new PDDocumentNameDictionary(pd.getDocumentCatalog());
            catalogNames.setDests(tree);
            pd.getDocumentCatalog().setNames(catalogNames);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pd.save(out);
            return out.toByteArray();
        }
    }
}
