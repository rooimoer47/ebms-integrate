package dev.ebms.adapter.out.cpa;

import dev.ebms.domain.Cpa;
import dev.ebms.domain.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.xml.sax.SAXException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

class CpaXmlParser {

    private static final Logger log = LoggerFactory.getLogger(CpaXmlParser.class);

    static final String CPA_NS = "http://www.oasis-open.org/committees/ebxml-cppa/schema/cpp-cpa-2_0.xsd";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";

    /**
     * Clients send party ids as {@code urn:osb:oin:<20-digit OIN>}; CPAs carry the bare OIN in
     * {@code PartyId}, and cpa-service configures its own ids bare too
     * ({@code cpa.default.server.oin}). A literal comparison between the two forms matches
     * nothing, so both sides are reduced to the bare OIN before comparing.
     */
    private static final String OIN_PREFIX = "urn:osb:oin:";

    /**
     * Parses an uploaded document. Takes bytes rather than a {@link String}: the encoding is the
     * one the XML prolog declares, and an HTTP {@code Content-Type} charset -- which the caller
     * does not set -- must not get to decide it.
     *
     * @param source a label for log messages (a filename, or the cpaId) -- never read from disk
     * @param ourPartyIds every party id that is us; see {@link #OIN_PREFIX}
     */
    Cpa parse(byte[] document, String source, List<String> ourPartyIds)
            throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
        Document doc = parseXml(document);
        XPath xpath = buildXpath();

        String cpaId = extractCpaId(doc);
        if (cpaId.isBlank()) {
            throw new IllegalArgumentException("Missing cpaid attribute in CPA: " + source);
        }

        PartyInfoPair parties = selectPartyInfo(doc, xpath, ourPartyIds, source);
        ReliableMessaging rm = extractReliableMessaging(doc, xpath);

        return new Cpa(
                cpaId,
                extractParty(parties.ours(), xpath),
                extractParty(parties.partner(), xpath),
                extractTransportUrl(parties.partner(), xpath),
                rm.ackRequested(),
                rm.duplicateElimination(),
                rm.retries(),
                rm.retryInterval(),
                extractCertificate(parties.partner(), xpath, source));
    }

    /** Agreement lifecycle, which is what validation judges and the projection does not model. */
    record Lifecycle(String status, Instant start, Instant end) {}

    /**
     * Reads Status, Start and End. Separate from {@link #parse}: the projection the message
     * paths use does not model agreement validity, and reading it there would invite expiry to
     * be enforced on a read path, where a CPA would silently vanish mid-flight.
     */
    Lifecycle readLifecycle(byte[] document)
            throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
        Document doc = parseXml(document);
        XPath xpath = buildXpath();
        Node status = (Node) xpath.evaluate(
                "/cpa:CollaborationProtocolAgreement/cpa:Status", doc, XPathConstants.NODE);
        String statusValue = null;
        if (status instanceof Element element) {
            statusValue = element.getAttributeNS(CPA_NS, "value");
            if (statusValue.isBlank()) {
                statusValue = element.getAttribute("value");
            }
        }
        return new Lifecycle(
                statusValue == null || statusValue.isBlank() ? null : statusValue,
                instantAt(xpath, doc, "Start"),
                instantAt(xpath, doc, "End"));
    }

    private Instant instantAt(XPath xpath, Document doc, String element) throws XPathExpressionException {
        String value = xpath.evaluate(
                "/cpa:CollaborationProtocolAgreement/cpa:" + element, doc).trim();
        if (value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(element + " is not a valid dateTime: " + value, e);
        }
    }

    Cpa parse(Path file, List<String> ourPartyIds)
            throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
        return parse(Files.readAllBytes(file), String.valueOf(file.getFileName()), ourPartyIds);
    }

    private record PartyInfoPair(Node ours, Node partner) {}

    /**
     * A CPA describes both parties; pick ours by party id.
     *
     * <p>There is deliberately no fallback to document order. Taking the first element as ours
     * means the transport URL and the encryption certificate come from whichever element happened
     * to be second -- so a CPA that lists us second would have us posting our own messages to
     * ourselves, encrypted to our own certificate, with nothing in the logs to say so. A CPA whose
     * parties we cannot identify is a CPA we cannot route, and it fails here.
     */
    private PartyInfoPair selectPartyInfo(Document doc, XPath xpath, List<String> ourPartyIds, String source)
            throws XPathExpressionException {
        NodeList partyInfoNodes = (NodeList) xpath.evaluate(
                "/cpa:CollaborationProtocolAgreement/cpa:PartyInfo", doc, XPathConstants.NODESET);
        if (partyInfoNodes.getLength() < 2) {
            throw new IllegalArgumentException("CPA must have at least 2 PartyInfo elements: " + source);
        }

        Node ours = null;
        Node partner = null;
        List<String> found = new ArrayList<>();
        for (int i = 0; i < partyInfoNodes.getLength(); i++) {
            Node partyInfo = partyInfoNodes.item(i);
            String partyId = extractPartyId(partyInfo, xpath);
            found.add(partyId);
            if (ours == null && isOurs(partyId, ourPartyIds)) {
                ours = partyInfo;
            } else {
                partner = partyInfo;
            }
        }

        if (ours == null || partner == null) {
            throw new IllegalArgumentException(
                    "Cannot tell which party is us in CPA " + source + ": it names " + found
                            + " and ebms.host-party-ids is " + ourPartyIds
                            + ". Routing would be a guess, so the CPA is rejected.");
        }
        return new PartyInfoPair(ours, partner);
    }

    private boolean isOurs(String partyId, List<String> ourPartyIds) {
        if (partyId == null || partyId.isBlank() || ourPartyIds == null) {
            return false;
        }
        String bare = bareOin(partyId);
        return ourPartyIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .anyMatch(id -> bare.equalsIgnoreCase(bareOin(id)));
    }

    private String bareOin(String partyId) {
        String trimmed = partyId.trim();
        return trimmed.regionMatches(true, 0, OIN_PREFIX, 0, OIN_PREFIX.length())
                ? trimmed.substring(OIN_PREFIX.length())
                : trimmed;
    }

    private record ReliableMessaging(boolean ackRequested, boolean duplicateElimination,
                                     int retries, Duration retryInterval) {}

    private ReliableMessaging extractReliableMessaging(Document doc, XPath xpath) throws XPathExpressionException {
        Node rm = (Node) xpath.evaluate(".//cpa:ReliableMessaging", doc, XPathConstants.NODE);
        if (rm == null) {
            return new ReliableMessaging(false, false, 3, Duration.ofSeconds(60));
        }

        boolean duplicateElimination =
                ((NodeList) xpath.evaluate("cpa:DuplicateElimination", rm, XPathConstants.NODESET)).getLength() > 0;

        int retries = 3;
        String retriesStr = xpath.evaluate("cpa:Retries", rm).trim();
        if (!retriesStr.isBlank()) {
            retries = Integer.parseInt(retriesStr);
        }

        Duration retryInterval = Duration.ofSeconds(60);
        String intervalStr = xpath.evaluate("cpa:RetryInterval", rm).trim();
        if (!intervalStr.isBlank()) {
            retryInterval = Duration.parse(intervalStr);
        }

        return new ReliableMessaging(true, duplicateElimination, retries, retryInterval);
    }

    private String extractCpaId(Document doc) {
        String cpaId = doc.getDocumentElement().getAttributeNS(CPA_NS, "cpaid");
        if (cpaId.isBlank()) {
            cpaId = doc.getDocumentElement().getAttribute("cpaid");
        }
        return cpaId;
    }

    private String extractPartyId(Node partyInfo, XPath xpath) throws XPathExpressionException {
        return xpath.evaluate("cpa:PartyId", partyInfo).trim();
    }

    private Party extractParty(Node partyInfo, XPath xpath) throws XPathExpressionException {
        Node partyIdNode = (Node) xpath.evaluate("cpa:PartyId", partyInfo, XPathConstants.NODE);
        if (partyIdNode == null) {
            throw new IllegalArgumentException("PartyInfo missing PartyId element");
        }
        String partyId = partyIdNode.getTextContent().trim();
        Element partyIdEl = (Element) partyIdNode;
        String partyIdType = partyIdEl.getAttributeNS(CPA_NS, "type");
        if (partyIdType.isBlank()) {
            partyIdType = partyIdEl.getAttribute("type");
        }
        String role = extractRole(partyInfo, xpath);
        return new Party(partyId, partyIdType.isBlank() ? null : partyIdType, role);
    }

    /**
     * The party's ebMS role, from CollaborationRole/Role/@name. Optional: a CPA that does not
     * declare roles yields a null role, and no eb:Role is then written to the header.
     */
    private String extractRole(Node partyInfo, XPath xpath) throws XPathExpressionException {
        Node roleNode = (Node) xpath.evaluate(
                "cpa:CollaborationRole/cpa:Role", partyInfo, XPathConstants.NODE);
        if (roleNode == null) {
            return null;
        }
        Element roleEl = (Element) roleNode;
        String name = roleEl.getAttributeNS(CPA_NS, "name");
        if (name.isBlank()) {
            name = roleEl.getAttribute("name");
        }
        return name.isBlank() ? null : name;
    }

    private X509Certificate extractCertificate(Node partyInfo, XPath xpath, String source) {
        try {
            String b64 = xpath.evaluate(
                    "cpa:Certificate/ds:KeyInfo/ds:X509Data/ds:X509Certificate", partyInfo).trim();
            if (b64.isBlank()) return null;
            byte[] der = Base64.getDecoder().decode(b64.replaceAll("\\s+", ""));
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            log.debug("No certificate in CPA {}: {}", source, e.getMessage());
            return null;
        }
    }

    private String extractTransportUrl(Node partnerPartyInfo, XPath xpath) throws XPathExpressionException {
        Node endpointNode = (Node) xpath.evaluate(
                "cpa:Transport/cpa:TransportReceiver/cpa:Endpoint", partnerPartyInfo, XPathConstants.NODE);
        if (endpointNode == null) {
            return "";
        }
        Element endpoint = (Element) endpointNode;
        String url = endpoint.getAttributeNS(XLINK_NS, "href");
        if (url.isBlank()) {
            url = endpoint.getAttributeNS(CPA_NS, "uri");
        }
        if (url.isBlank()) {
            url = endpoint.getAttribute("uri");
        }
        return url;
    }

    /**
     * The document now arrives over the network rather than from a mounted volume, so the
     * hardening below is load-bearing: no DOCTYPE, no external entities, no XInclude.
     */
    private Document parseXml(byte[] document) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));
    }

    private XPath buildXpath() {
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return switch (prefix) {
                    case "cpa" -> CPA_NS;
                    case "xlink" -> XLINK_NS;
                    case "ds" -> DS_NS;
                    default -> javax.xml.XMLConstants.NULL_NS_URI;
                };
            }
            @Override public String getPrefix(String ns) { return null; }
            @Override public Iterator<String> getPrefixes(String ns) { return null; }
        });
        return xpath;
    }
}
