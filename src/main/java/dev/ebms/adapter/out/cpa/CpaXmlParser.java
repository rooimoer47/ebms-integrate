package dev.ebms.adapter.out.cpa;

import dev.ebms.domain.Cpa;
import dev.ebms.domain.Party;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;

class CpaXmlParser {

    static final String CPA_NS = "http://www.oasis-open.org/committees/ebxml-cppa/schema/cpp-cpa-2_0.xsd";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    Cpa parse(Path file, String ourPartyId) throws Exception {
        Document doc = parseXml(file);
        XPath xpath = buildXpath();

        String cpaId = extractCpaId(doc);
        if (cpaId.isBlank()) {
            throw new IllegalArgumentException("Missing cpaid attribute in CPA file: " + file);
        }

        NodeList partyInfoNodes = (NodeList) xpath.evaluate(
                "/cpa:CollaborationProtocolAgreement/cpa:PartyInfo", doc, XPathConstants.NODESET);
        if (partyInfoNodes.getLength() < 2) {
            throw new IllegalArgumentException("CPA must have at least 2 PartyInfo elements: " + file);
        }

        Node ourPartyInfo = null;
        Node partnerPartyInfo = null;

        if (ourPartyId != null && !ourPartyId.isBlank()) {
            for (int i = 0; i < partyInfoNodes.getLength(); i++) {
                Node pi = partyInfoNodes.item(i);
                String partyId = extractPartyId(pi, xpath);
                if (ourPartyId.equals(partyId)) {
                    ourPartyInfo = pi;
                } else {
                    partnerPartyInfo = pi;
                }
            }
        }
        if (ourPartyInfo == null) {
            ourPartyInfo = partyInfoNodes.item(0);
            partnerPartyInfo = partyInfoNodes.item(1);
        }

        Party fromParty = extractParty(ourPartyInfo, xpath);
        Party toParty = extractParty(partnerPartyInfo, xpath);
        String transportUrl = extractTransportUrl(partnerPartyInfo, xpath);

        Node rm = (Node) xpath.evaluate(".//cpa:ReliableMessaging", doc, XPathConstants.NODE);
        boolean ackRequested = rm != null;
        boolean duplicateElimination = rm != null &&
                ((NodeList) xpath.evaluate("cpa:DuplicateElimination", rm, XPathConstants.NODESET)).getLength() > 0;
        int retries = 3;
        Duration retryInterval = Duration.ofSeconds(60);
        if (rm != null) {
            String retriesStr = xpath.evaluate("cpa:Retries", rm).trim();
            if (!retriesStr.isBlank()) {
                retries = Integer.parseInt(retriesStr);
            }
            String intervalStr = xpath.evaluate("cpa:RetryInterval", rm).trim();
            if (!intervalStr.isBlank()) {
                retryInterval = Duration.parse(intervalStr);
            }
        }

        return new Cpa(cpaId, fromParty, toParty, transportUrl,
                ackRequested, duplicateElimination, retries, retryInterval);
    }

    private String extractCpaId(Document doc) {
        String cpaId = doc.getDocumentElement().getAttributeNS(CPA_NS, "cpaid");
        if (cpaId.isBlank()) {
            cpaId = doc.getDocumentElement().getAttribute("cpaid");
        }
        return cpaId;
    }

    private String extractPartyId(Node partyInfo, XPath xpath) throws Exception {
        return xpath.evaluate("cpa:PartyId", partyInfo).trim();
    }

    private Party extractParty(Node partyInfo, XPath xpath) throws Exception {
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

    private String extractTransportUrl(Node partnerPartyInfo, XPath xpath) throws Exception {
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

    private Document parseXml(Path file) throws Exception {
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
                    default -> javax.xml.XMLConstants.NULL_NS_URI;
                };
            }
            @Override public String getPrefix(String ns) { return null; }
            @Override public Iterator<String> getPrefixes(String ns) { return null; }
        });
        return xpath;
    }
}
