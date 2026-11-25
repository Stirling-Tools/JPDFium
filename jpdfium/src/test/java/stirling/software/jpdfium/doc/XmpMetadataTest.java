package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmpMetadataTest {

    @Test
    void detectsClaimsWithoutMistakingSchemaDescriptionsForClaims() {
        assertEquals(XmpMetadata.Claim.PDF_A, XmpMetadata.claim(pdfa("2", "B")));
        assertEquals(XmpMetadata.Claim.PDF_X,
                XmpMetadata.claim("<pdfx:GTS_PDFXVersion>PDF/X-4</pdfx:GTS_PDFXVersion>"));
        assertEquals(XmpMetadata.Claim.PDF_A_AND_X,
                XmpMetadata.claim(pdfa("2", "B").replace("</rdf:RDF>",
                        description("xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\"",
                                "<pdfx:GTS_PDFXVersion>PDF/X-4</pdfx:GTS_PDFXVersion>")
                                + "</rdf:RDF>")));
        assertEquals(XmpMetadata.Claim.NONE, XmpMetadata.claim("""
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description xmlns:pdfaSchema="http://www.aiim.org/pdfa/ns/schema#">
                   <pdfaSchema:prefix>pdfaid</pdfaSchema:prefix>
                  </rdf:Description>
                 </rdf:RDF>
                </x:xmpmeta>
                """));
        assertEquals(XmpMetadata.Claim.PDF_A, XmpMetadata.claim(pdfa("2", "B")
                .replace("pdfaid:", "a:")
                .replace("xmlns:pdfaid", "xmlns:a")));
    }

    @Test
    void detectsPdfUaClaimWithStandardAndAlternatePrefixes() {
        String claim = pdfa("2", "B").replace("</rdf:RDF>",
                pdfua("1") + "</rdf:RDF>");
        assertTrue(XmpMetadata.hasPdfUaClaim(claim));
        String aliased = claim
                .replace("pdfuaid:", "u:")
                .replace("xmlns:pdfuaid", "xmlns:u");
        assertTrue(XmpMetadata.hasPdfUaClaim(aliased));
        assertFalse(XmpMetadata.hasPdfUaClaim(pdfa("2", "B")));
    }

    @Test
    void replacementKeepsMissingPdfUaIdentification() {
        String existing = pdfa("2", "B").replace("</rdf:RDF>",
                description("xmlns:dc=\"http://purl.org/dc/elements/1.1/\"",
                        "<dc:title>Existing title</dc:title>")
                        + pdfua("2") + "</rdf:RDF>");
        String merged = XmpMetadata.mergeConformance(existing, pdfa("3", "B"));

        assertTrue(merged.contains("pdfuaid:part"), merged);
        assertTrue(merged.contains("pdfuaid:rev"), merged);
        assertTrue(merged.contains(">2<"), merged);
        assertTrue(merged.contains(">2024<"), merged);
        assertTrue(merged.contains("Existing title"), merged);

        String withTitle = pdfa("3", "B").replace("</rdf:RDF>",
                description("xmlns:dc=\"http://purl.org/dc/elements/1.1/\"",
                        "<dc:title>New title</dc:title>")
                        + "</rdf:RDF>");
        String overridden = XmpMetadata.mergeConformance(existing, withTitle);
        assertTrue(overridden.contains("New title"), overridden);
        assertFalse(overridden.contains("Existing title"), overridden);
    }

    @Test
    void replacementKeepsMissingConformancePropertiesButHonoursOverrides() {
        String replacement = """
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
                   <pdfaid:part>3</pdfaid:part>
                  </rdf:Description>
                 </rdf:RDF>
                </x:xmpmeta>
                """;

        String existing = pdfa("2", "B").replace("</rdf:RDF>",
                description("xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\"",
                        "<pdfx:GTS_PDFXVersion>PDF/X-4</pdfx:GTS_PDFXVersion>")
                        + "</rdf:RDF>");
        String merged = XmpMetadata.mergeConformance(existing, replacement);

        assertTrue(merged.contains("<pdfaid:part>3</pdfaid:part>"),
                "the caller's version must win");
        assertTrue(merged.contains("<pdfaid:conformance") && merged.contains(">B</"),
                "missing conformance must be inherited");
        assertTrue(merged.contains("GTS_PDFXVersion"), "PDF/X must be inherited");
    }

    @Test
    void infoSynchronizationUpdatesTitleCreatorAndRemovesMissingFields() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Title", "Updated");
        values.put("Author", "Finance");
        values.put("Subject", null);
        values.put("Keywords", "quarterly");

        String synced = XmpMetadata.synchronizeInfo(
                pdfa("2", "B").replace("</rdf:RDF>", """
                        <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"
                            xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
                         <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Old</rdf:li></rdf:Alt></dc:title>
                         <dc:description><rdf:Alt><rdf:li xml:lang="x-default">Remove</rdf:li></rdf:Alt></dc:description>
                         <pdf:Keywords>old</pdf:Keywords>
                        </rdf:Description>
                        </rdf:RDF>"""), values);

        assertEquals(1, occurrences(synced, "<dc:title"));
        assertEquals(1, occurrences(synced, "<dc:creator"));
        assertTrue(synced.contains(">Updated<"));
        assertTrue(synced.contains(">Finance<"));
        assertFalse(synced.contains(">Remove<"));
        assertTrue(synced.contains(">quarterly<"));
    }

    @Test
    void infoSynchronizationConvertsPdfDatesToIso() {
        Map<String, String> values = Map.of("CreationDate", "D:20261023020355Z");
        String synced = XmpMetadata.synchronizeInfo(pdfa("2", "B"), values);
        assertTrue(synced.contains(">2026-10-23T02:03:55Z<"), synced);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = value.indexOf(needle); index >= 0;
                index = value.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private static String pdfa(String part, String conformance) {
        return """
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
                   <pdfaid:part>%s</pdfaid:part>
                   <pdfaid:conformance>%s</pdfaid:conformance>
                  </rdf:Description>
                 </rdf:RDF>
                </x:xmpmeta>
                """.formatted(part, conformance);
    }

    private static String pdfua(String part) {
        return description("xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\"",
                "<pdfuaid:part>" + part + "</pdfuaid:part>"
                        + "<pdfuaid:rev>2024</pdfuaid:rev>");
    }

    private static String description(String namespaces, String body) {
        return "<rdf:Description rdf:about=\"\" " + namespaces + ">" + body + "</rdf:Description>";
    }
}
