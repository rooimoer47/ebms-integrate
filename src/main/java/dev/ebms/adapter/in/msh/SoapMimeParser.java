package dev.ebms.adapter.in.msh;

import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Party;
import dev.ebms.domain.Payload;
import dev.ebms.domain.exception.MessageParseException;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Component
public class SoapMimeParser {

    static final String EB_NS = "http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd";
    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    public EbmsMessage parse(byte[] body, String contentType) {
        try {
            if (contentType != null && contentType.toLowerCase().contains("multipart/related")) {
                return parseMultipart(body, contentType);
            }
            return parseSinglePart(body);
        } catch (MessageParseException e) {
            throw e;
        } catch (Exception e) {
            String code = (contentType != null && contentType.toLowerCase().contains("multipart/related"))
                    ? "MimeRelated" : "OtherXml";
            throw new MessageParseException("Failed to parse ebMS message", code, e);
        }
    }

    private EbmsMessage parseMultipart(byte[] body, String contentType) throws MessagingException, IOException {
        ByteArrayDataSource ds = new ByteArrayDataSource(body, contentType);
        MimeMultipart multipart = new MimeMultipart(ds);

        BodyPart soapPart = multipart.getBodyPart(0);
        Document doc = parseXml(soapPart.getInputStream());

        List<Payload> payloads = new ArrayList<>();
        for (int i = 1; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String[] contentIdHeader = part.getHeader("Content-Id");
            String contentId = contentIdHeader != null && contentIdHeader.length > 0
                    ? stripAngleBrackets(contentIdHeader[0])
                    : "part-" + i;
            payloads.add(new Payload(contentId, part.getContentType(), part.getInputStream().readAllBytes()));
        }

        return extractMessage(doc, payloads);
    }

    private EbmsMessage parseSinglePart(byte[] body) {
        Document doc = parseXml(new ByteArrayInputStream(body));
        return extractMessage(doc, List.of());
    }

    private EbmsMessage extractMessage(Document doc, List<Payload> payloads) {
        try {
            XPath xpath = buildXpath();

            String messageId = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:MessageData/eb:MessageId", doc);
            String conversationId = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:ConversationId", doc);
            String cpaId = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:CPAId", doc);
            String service = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:Service", doc);
            String action = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:Action", doc);
            String timestampStr = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:MessageData/eb:Timestamp", doc);
            String fromPartyId = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:From/eb:PartyId", doc);
            String fromPartyType = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:From/eb:PartyId/@eb:type", doc);
            String toPartyId = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:To/eb:PartyId", doc);
            String toPartyType = xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:To/eb:PartyId/@eb:type", doc);

            NodeList ackRequestedNodes = (NodeList) xpath.evaluate(
                    "/soap:Envelope/soap:Header/eb:AckRequested", doc, XPathConstants.NODESET);
            boolean ackRequested = ackRequestedNodes.getLength() > 0;

            String refToMessageId = xpath.evaluate(
                    "/soap:Envelope/soap:Header/eb:Acknowledgment/eb:RefToMessageId", doc);
            if (refToMessageId == null || refToMessageId.isBlank()) {
                refToMessageId = xpath.evaluate(
                        "/soap:Envelope/soap:Header/eb:MessageHeader/eb:MessageData/eb:RefToMessageId", doc);
            }

            validateRequired(messageId, "MessageId");
            validateRequired(conversationId, "ConversationId");
            validateRequired(cpaId, "CPAId");
            validateRequired(service, "Service");
            validateRequired(action, "Action");
            validateRequired(fromPartyId, "From/PartyId");
            validateRequired(toPartyId, "To/PartyId");

            Instant timestamp = timestampStr != null && !timestampStr.isBlank()
                    ? Instant.parse(timestampStr)
                    : Instant.now();

            return EbmsMessage.newInbound(messageId, conversationId, cpaId,
                    new Party(fromPartyId, nullIfBlank(fromPartyType)),
                    new Party(toPartyId, nullIfBlank(toPartyType)),
                    service, action, timestamp, payloads, ackRequested,
                    nullIfBlank(refToMessageId));

        } catch (MessageParseException e) {
            throw e;
        } catch (Exception e) {
            throw new MessageParseException("Failed to extract ebMS header fields", e);
        }
    }

    private Document parseXml(InputStream is) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(is));
        } catch (Exception e) {
            throw new MessageParseException("Failed to parse SOAP XML", e);
        }
    }

    private XPath buildXpath() {
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return switch (prefix) {
                    case "soap" -> SOAP_NS;
                    case "eb" -> EB_NS;
                    default -> javax.xml.XMLConstants.NULL_NS_URI;
                };
            }

            @Override
            public String getPrefix(String namespaceURI) { return null; }

            @Override
            public Iterator<String> getPrefixes(String namespaceURI) { return null; }
        });
        return xpath;
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new MessageParseException("Required field missing: " + fieldName, "Inconsistent");
        }
    }

    private String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private String stripAngleBrackets(String contentId) {
        String s = contentId.trim();
        if (s.startsWith("<") && s.endsWith(">")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
