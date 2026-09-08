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
import java.nio.file.Path;
import org.xml.sax.SAXException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;

class CpaXmlParser {

    private static final Logger log = LoggerFactory.getLogger(CpaXmlParser.class);

    static final String CPA_NS = "http://www.oasis-open.org/committees/ebxml-cppa/schema/cpp-cpa-2_0.xsd";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";

    Cpa parse(Path file, String ourPartyId) throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
        Document doc = parseXml(file);
        XPath xpath = buildXpath();

        String cpaId = extractCpaId(doc);
        if (cpaId.isBlank()) {
            throw new IllegalArgumentException("Missing cpaid attribute in CPA file: " + file);
        }

        PartyInfoPair parties = selectPartyInfo(doc, xpath, ourPartyId, file);
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
                extractCertificate(parties.partner(), xpath, file));
    }

    private record PartyInfoPair(Node ours, Node partner) {}

    /** A CPA describes both parties; pick ours by party id, falling back to document order. */
    private PartyInfoPair selectPartyInfo(Document doc, XPath xpath, String ourPartyId, Path file)
            throws XPathExpressionException {
        NodeList partyInfoNodes = (NodeList) xpath.evaluate(
                "/cpa:CollaborationProtocolAgreement/cpa:PartyInfo", doc, XPathConstants.NODESET);
        if (partyInfoNodes.getLength() < 2) {
            throw new IllegalArgumentException("CPA must have at least 2 PartyInfo elements: " + file);
        }

        Node ours = null;
        Node partner = null;
        if (ourPartyId != null && !ourPartyId.isBlank()) {
            for (int i = 0; i < partyInfoNodes.getLength(); i++) {
                Node partyInfo = partyInfoNodes.item(i);
                if (ourPartyId.equals(extractPartyId(partyInfo, xpath))) {
                    ours = partyInfo;
                } else {
                    partner = partyInfo;
                }
            }
        }
        if (ours == null) {
            ours = partyInfoNodes.item(0);
            partner = partyInfoNodes.item(1);
        }
        return new PartyInfoPair(ours, partner);
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
        return new Party(partyId, partyIdType.isBlank() ? null : partyIdType);
    }

    private X509Certificate extractCertificate(Node partyInfo, XPath xpath, Path file) {
        try {
            String b64 = xpath.evaluate(
                    "cpa:Certificate/ds:KeyInfo/ds:X509Data/ds:X509Certificate", partyInfo).trim();
            if (b64.isBlank()) return null;
            byte[] der = Base64.getDecoder().decode(b64.replaceAll("\\s+", ""));
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            log.debug("No certificate in CPA {}: {}", file.getFileName(), e.getMessage());
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

    private Document parseXml(Path file) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(file.toFile());
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
