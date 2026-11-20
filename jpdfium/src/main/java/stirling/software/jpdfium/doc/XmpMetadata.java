package stirling.software.jpdfium.doc;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * XMP preservation and /Info synchronization for conformant PDFs.
 *
 * <p>The editor must not silently turn a PDF/A, PDF/X, or PDF/UA document into
 * one that merely looks ordinary. Replacing the packet keeps the identification
 * and extension descriptions that make the conformance claim, and /Info changes
 * are reflected into the matching XMP properties so the two views stay
 * consistent.
 */
final class XmpMetadata {

    private static final String NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String NS_DC = "http://purl.org/dc/elements/1.1/";
    private static final String NS_PDF = "http://ns.adobe.com/pdf/1.3/";
    private static final String NS_XMP = "http://ns.adobe.com/xap/1.0/";
    private static final String NS_XML = "http://www.w3.org/XML/1998/namespace";
    private static final String NS_PDFA_ID = "http://www.aiim.org/pdfa/ns/id/";
    private static final String NS_PDFA_EXT = "http://www.aiim.org/pdfa/ns/extension/";
    private static final String NS_PDFX = "http://ns.adobe.com/pdfx/1.3/";
    private static final String NS_PDFX_ID = "http://www.npes.org/pdfx/ns/id/";
    private static final String NS_PDFUA_ID = "http://www.aiim.org/pdfua/ns/id/";

    private static final Set<String> PDFA_PROPERTIES = Set.of("part", "conformance");
    private static final Set<String> PDFA_EXTENSION_PROPERTIES = Set.of("schemas");
    private static final Set<String> PDFX_PROPERTIES =
            Set.of("GTS_PDFXVersion", "GTS_PDFXConformance", "PDFXVersion", "PDFXConformance");
    private static final Set<String> PDFX_ID_PROPERTIES = Set.of("GTS_PDFXVersion");
    private static final Set<String> PDFUA_PROPERTIES = Set.of("part", "rev");

    enum Claim {
        NONE,
        PDF_A,
        PDF_X,
        PDF_A_AND_X
    }

    private XmpMetadata() {}

    static Claim claim(String xmp) {
        if (xmp == null || xmp.isEmpty()) return Claim.NONE;
        boolean pdfa = xmp.contains("pdfaid:part") || xmp.contains("GTS_PDFA1");
        boolean pdfx = xmp.contains("GTS_PDFXVersion") || xmp.contains("pdfxid:GTS_PDFXVersion");
        if (!pdfa || !pdfx) {
            try {
                Document doc = parse(xmp);
                pdfa |= hasProperty(doc, NS_PDFA_ID, "part");
                pdfx |= hasProperty(doc, NS_PDFX, "GTS_PDFXVersion")
                        || hasProperty(doc, NS_PDFX_ID, "GTS_PDFXVersion");
            } catch (IllegalArgumentException ignored) {
                // The raw markers above remain the fallback for malformed packets.
            }
        }
        if (pdfa && pdfx) return Claim.PDF_A_AND_X;
        if (pdfa) return Claim.PDF_A;
        if (pdfx) return Claim.PDF_X;
        return Claim.NONE;
    }

    /** PDF/UA uses a separate identification schema and can coexist with PDF/A or PDF/X. */
    static boolean hasPdfUaClaim(String xmp) {
        if (xmp == null || xmp.isEmpty()) return false;
        if (xmp.contains("pdfuaid:part")) return true;
        try {
            return hasProperty(parse(xmp), NS_PDFUA_ID, "part");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Copies conformance identification that the replacement packet omitted.
     * User-provided properties win; only missing properties are inherited.
     */
    static String mergeConformance(String existing, String replacement) {
        Claim claim = claim(existing);
        boolean pdfua = hasPdfUaClaim(existing);
        if (claim == Claim.NONE && !pdfua) return replacement;

        Document oldDoc = parse(existing);
        Document newDoc = parse(replacement);
        Element oldRdf = rdf(oldDoc);
        Element newRdf = rdf(newDoc);
        if (oldRdf == null || newRdf == null) {
            throw new IllegalArgumentException("XMP packet has no rdf:RDF element");
        }

        boolean changed = false;
        if (claim == Claim.PDF_A || claim == Claim.PDF_A_AND_X) {
            changed |= copyMissing(newDoc, newRdf, oldDoc, NS_PDFA_ID, PDFA_PROPERTIES);
            changed |= copyMissing(newDoc, newRdf, oldDoc, NS_PDFA_EXT, PDFA_EXTENSION_PROPERTIES);
        }
        if (claim == Claim.PDF_X || claim == Claim.PDF_A_AND_X) {
            changed |= copyMissing(newDoc, newRdf, oldDoc, NS_PDFX, PDFX_PROPERTIES);
            changed |= copyMissing(newDoc, newRdf, oldDoc, NS_PDFX_ID, PDFX_ID_PROPERTIES);
        }
        if (pdfua) {
            changed |= copyMissing(newDoc, newRdf, oldDoc, NS_PDFUA_ID, PDFUA_PROPERTIES);
            // PDF/UA requires a document title. Keep the existing one when the
            // replacement packet omits it; an explicit replacement title wins.
            changed |= copyMissing(newDoc, newRdf, oldDoc, NS_DC, Set.of("title"));
        }
        return changed ? serialize(newDoc) : replacement;
    }

    /**
     * Reflects the effective /Info values into the standard XMP properties.
     * Only the fields present in {@code values} are touched; a null value
     * removes the corresponding XMP property.
     */
    static String synchronizeInfo(String xmp, Map<String, String> values) {
        if (xmp == null || xmp.isEmpty() || values.isEmpty()) return xmp;
        Document doc = parse(xmp);
        Element rdf = rdf(doc);
        if (rdf == null) {
            throw new IllegalArgumentException("XMP packet has no rdf:RDF element");
        }

        boolean changed = false;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            changed |= switch (entry.getKey()) {
                case "Title" -> setAlt(doc, rdf, NS_DC, "dc:title", "title", value);
                case "Author" -> setSeq(doc, rdf, NS_DC, "dc:creator", "creator", value);
                case "Subject" -> setAlt(doc, rdf, NS_DC, "dc:description", "description", value);
                case "Keywords" -> setSimple(doc, rdf, NS_PDF, "pdf:Keywords", "Keywords", value);
                case "Creator" -> setSimple(doc, rdf, NS_XMP, "xmp:CreatorTool", "CreatorTool", value);
                case "Producer" -> setSimple(doc, rdf, NS_PDF, "pdf:Producer", "Producer", value);
                case "CreationDate" ->
                        setSimple(doc, rdf, NS_XMP, "xmp:CreateDate", "CreateDate", xmpDate(value));
                case "ModDate" ->
                        setSimple(doc, rdf, NS_XMP, "xmp:ModifyDate", "ModifyDate", xmpDate(value));
                case "Trapped" -> setSimple(doc, rdf, NS_PDF, "pdf:Trapped", "Trapped", value);
                default -> false;
            };
        }
        return changed ? serialize(doc) : xmp;
    }

    private static boolean copyMissing(Document newDoc, Element newRdf, Document oldDoc,
            String namespace, Set<String> names) {
        Element description = null;
        boolean changed = false;
        for (String name : names) {
            if (hasProperty(newDoc, namespace, name)) continue;
            Node value = firstProperty(oldDoc, namespace, name);
            if (value == null) continue;

            if (description == null) {
                description = newDoc.createElementNS(NS_RDF, "rdf:Description");
                description.setAttributeNS(NS_RDF, "rdf:about", "");
                newRdf.appendChild(description);
            }
            if (value instanceof Attr attr) {
                description.setAttributeNS(namespace, attr.getName(), attr.getValue());
            } else {
                description.appendChild(newDoc.importNode(value, true));
            }
            changed = true;
        }
        return changed;
    }

    private static boolean hasProperty(Document doc, String namespace, String name) {
        if (doc.getElementsByTagNameNS(namespace, name).getLength() > 0) return true;
        for (Element description : descriptions(doc)) {
            if (description.hasAttributeNS(namespace, name)) return true;
        }
        return false;
    }

    private static Node firstProperty(Document doc, String namespace, String name) {
        NodeList elements = doc.getElementsByTagNameNS(namespace, name);
        if (elements.getLength() > 0) return elements.item(0);
        for (Element description : descriptions(doc)) {
            if (description.hasAttributeNS(namespace, name)) {
                return description.getAttributeNodeNS(namespace, name);
            }
        }
        return null;
    }

    private static boolean setSimple(Document doc, Element rdf, String namespace, String qName,
            String localName, String value) {
        Attr attribute = findAttribute(doc, namespace, localName);
        if (attribute != null) {
            if (value == null || value.isBlank()) {
                attribute.getOwnerElement().removeAttributeNode(attribute);
                return true;
            }
            if (value.equals(attribute.getValue())) return false;
            attribute.setValue(value);
            return true;
        }

        NodeList elements = doc.getElementsByTagNameNS(namespace, localName);
        if (value == null || value.isBlank()) {
            if (elements.getLength() == 0) return false;
            for (int i = elements.getLength() - 1; i >= 0; i--) {
                Node node = elements.item(i);
                node.getParentNode().removeChild(node);
            }
            return true;
        }

        if (elements.getLength() > 0) {
            Element element = (Element) elements.item(0);
            if (value.equals(element.getTextContent())) {
                return removeExtras(elements, element);
            }
            element.setTextContent(value);
            removeExtras(elements, element);
            return true;
        }

        Element description = description(doc, rdf);
        Element element = doc.createElementNS(namespace, qName);
        element.setTextContent(value);
        description.appendChild(element);
        return true;
    }

    private static boolean setAlt(Document doc, Element rdf, String namespace, String qName,
            String localName, String value) {
        NodeList elements = doc.getElementsByTagNameNS(namespace, localName);
        if (value == null || value.isBlank()) {
            if (elements.getLength() == 0) return false;
            for (int i = elements.getLength() - 1; i >= 0; i--) {
                Node node = elements.item(i);
                node.getParentNode().removeChild(node);
            }
            return true;
        }

        Element element;
        boolean duplicates = false;
        if (elements.getLength() == 0) {
            element = doc.createElementNS(namespace, qName);
            description(doc, rdf).appendChild(element);
        } else {
            element = (Element) elements.item(0);
            duplicates = removeExtras(elements, element);
        }
        Element alt = firstChild(element, NS_RDF, "Alt");
        if (alt == null) {
            element.setTextContent("");
            alt = doc.createElementNS(NS_RDF, "rdf:Alt");
            element.appendChild(alt);
        }
        Element item = null;
        for (Element li : children(alt, NS_RDF, "li")) {
            if ("x-default".equals(li.getAttributeNS(NS_XML, "lang"))) {
                item = li;
                break;
            }
        }
        if (item == null) {
            item = doc.createElementNS(NS_RDF, "rdf:li");
            item.setAttributeNS(NS_XML, "xml:lang", "x-default");
            alt.appendChild(item);
        }
        if (value.equals(item.getTextContent())) return duplicates;
        item.setTextContent(value);
        return true;
    }

    private static boolean setSeq(Document doc, Element rdf, String namespace, String qName,
            String localName, String value) {
        NodeList elements = doc.getElementsByTagNameNS(namespace, localName);
        if (value == null || value.isBlank()) {
            if (elements.getLength() == 0) return false;
            for (int i = elements.getLength() - 1; i >= 0; i--) {
                Node node = elements.item(i);
                node.getParentNode().removeChild(node);
            }
            return true;
        }

        Element element;
        boolean duplicates = false;
        if (elements.getLength() == 0) {
            element = doc.createElementNS(namespace, qName);
            description(doc, rdf).appendChild(element);
        } else {
            element = (Element) elements.item(0);
            duplicates = removeExtras(elements, element);
        }
        Element seq = firstChild(element, NS_RDF, "Seq");
        if (seq == null) {
            element.setTextContent("");
            seq = doc.createElementNS(NS_RDF, "rdf:Seq");
            element.appendChild(seq);
        }
        Element item = firstChild(seq, NS_RDF, "li");
        if (item == null) {
            item = doc.createElementNS(NS_RDF, "rdf:li");
            seq.appendChild(item);
        }
        boolean changed = duplicates || !value.equals(item.getTextContent());
        item.setTextContent(value);
        for (int i = seq.getChildNodes().getLength() - 1; i >= 0; i--) {
            Node node = seq.getChildNodes().item(i);
            if (node != item && node.getNodeType() == Node.ELEMENT_NODE
                    && NS_RDF.equals(node.getNamespaceURI())
                    && "li".equals(node.getLocalName())) {
                seq.removeChild(node);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean removeExtras(NodeList elements, Element keep) {
        boolean changed = false;
        for (int i = elements.getLength() - 1; i >= 0; i--) {
            Node node = elements.item(i);
            if (node != keep) {
                node.getParentNode().removeChild(node);
                changed = true;
            }
        }
        return changed;
    }

    private static Element description(Document doc, Element rdf) {
        Element description = firstChild(rdf, NS_RDF, "Description");
        if (description != null) return description;
        description = doc.createElementNS(NS_RDF, "rdf:Description");
        description.setAttributeNS(NS_RDF, "rdf:about", "");
        rdf.appendChild(description);
        return description;
    }

    private static Attr findAttribute(Document doc, String namespace, String localName) {
        for (Element description : descriptions(doc)) {
            Attr attr = description.getAttributeNodeNS(namespace, localName);
            if (attr != null) return attr;
        }
        return null;
    }

    private static Iterable<Element> descriptions(Document doc) {
        Set<Element> result = new LinkedHashSet<>();
        NodeList nodes = doc.getElementsByTagNameNS(NS_RDF, "Description");
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }
        return result;
    }

    private static Element rdf(Document doc) {
        NodeList nodes = doc.getElementsByTagNameNS(NS_RDF, "RDF");
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        for (Element child : children(parent, namespace, localName)) return child;
        return null;
    }

    private static Iterable<Element> children(Element parent, String namespace, String localName) {
        Set<Element> result = new LinkedHashSet<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && namespace.equals(node.getNamespaceURI())
                    && localName.equals(node.getLocalName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static Document parse(String xmp) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xmp)));
        } catch (Exception e) {
            throw new IllegalArgumentException("XMP packet is not well-formed XML", e);
        }
    }

    private static String serialize(Document doc) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("XMP packet could not be serialized", e);
        }
    }

    private static String xmpDate(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.startsWith("D:") ? value.substring(2) : value;
        if (v.length() < 4 || !digits(v, 0, 4)) return value;

        StringBuilder out = new StringBuilder(20);
        out.append(v, 0, 4);
        if (v.length() >= 6 && digits(v, 4, 6)) {
            out.append('-').append(v, 4, 6);
            if (v.length() >= 8 && digits(v, 6, 8)) {
                out.append('-').append(v, 6, 8);
                if (v.length() >= 14 && digits(v, 8, 14)) {
                    out.append('T').append(v, 8, 10).append(':')
                            .append(v, 10, 12).append(':').append(v, 12, 14);
                    if (v.length() > 14) {
                        char sign = v.charAt(14);
                        if (sign == 'Z') {
                            out.append('Z');
                        } else if ((sign == '+' || sign == '-') && v.length() >= 17) {
                            String offset = v.substring(15).replace("'", "");
                            out.append(sign);
                            if (offset.length() >= 2) out.append(offset, 0, 2);
                            if (offset.length() >= 4) out.append(':').append(offset, 2, 4);
                        }
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean digits(String value, int start, int end) {
        if (value.length() < end) return false;
        for (int i = start; i < end; i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
