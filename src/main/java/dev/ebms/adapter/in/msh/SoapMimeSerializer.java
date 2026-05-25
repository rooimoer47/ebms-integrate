package dev.ebms.adapter.in.msh;

import dev.ebms.application.port.out.OutboundMessageSerializer;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Payload;

import java.security.cert.X509Certificate;
import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static dev.ebms.adapter.in.msh.SoapMimeParser.EB_NS;
import static dev.ebms.adapter.in.msh.SoapMimeParser.SOAP_NS;

@Component
public class SoapMimeSerializer implements OutboundMessageSerializer {

    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final String ATTR_MUST_UNDERSTAND = "SOAP-ENV:mustUnderstand";
    private static final String ATTR_EB_VERSION = "eb:version";
    private static final String EB_PARTY_ID = "eb:PartyId";

    private final XmlSignatureService signatureService;
    private final XmlEncryptionService encryptionService;

    public SoapMimeSerializer(XmlSignatureService signatureService,
                               XmlEncryptionService encryptionService) {
        this.signatureService = signatureService;
        this.encryptionService = encryptionService;
    }

    @Override
    public SerializedMessage serialize(EbmsMessage message, X509Certificate recipientCert) {
        try {
            Document doc = buildSoapDocument(message);
            if (signatureService.isEnabled()) {
                signatureService.sign(doc);
            }

            List<Payload> payloads = message.payloads();
            if (recipientCert != null && !payloads.isEmpty()) {
                payloads = encryptionService.encryptPayloads(doc, payloads, recipientCert);
            }

            String soapXml = toXmlString(doc);

            if (payloads.isEmpty()) {
                return new SerializedMessage(soapXml.getBytes(), "text/xml; charset=UTF-8");
            }

            return buildMultipart(soapXml, payloads);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize ebMS message", e);
        }
    }

    private Document buildSoapDocument(EbmsMessage message) throws ParserConfigurationException {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

        Element envelope = doc.createElementNS(SOAP_NS, "SOAP-ENV:Envelope");
        envelope.setAttributeNS(XMLNS_NS, "xmlns:SOAP-ENV", SOAP_NS);
        envelope.setAttributeNS(XMLNS_NS, "xmlns:eb", EB_NS);
        doc.appendChild(envelope);

        Element header = doc.createElementNS(SOAP_NS, "SOAP-ENV:Header");
        envelope.appendChild(header);

        header.appendChild(buildMessageHeader(doc, message));

        if (message.ackRequested()) {
            Element ackRequested = doc.createElementNS(EB_NS, "eb:AckRequested");
            ackRequested.setAttributeNS(SOAP_NS, ATTR_MUST_UNDERSTAND, "1");
            ackRequested.setAttributeNS(EB_NS, ATTR_EB_VERSION, "2.0");
            ackRequested.setAttributeNS(EB_NS, "eb:signed", "false");
            header.appendChild(ackRequested);
        }

        if ("Acknowledgment".equals(message.action())) {
            header.appendChild(buildAcknowledgmentElement(doc, message));
        }

        Element body = doc.createElementNS(SOAP_NS, "SOAP-ENV:Body");
        envelope.appendChild(body);

        if (!message.payloads().isEmpty()) {
            body.appendChild(buildManifest(doc, message));
        }

        return doc;
    }

    private Element buildMessageHeader(Document doc, EbmsMessage message) {
        Element header = doc.createElementNS(EB_NS, "eb:MessageHeader");
        header.setAttributeNS(SOAP_NS, ATTR_MUST_UNDERSTAND, "1");
        header.setAttributeNS(EB_NS, ATTR_EB_VERSION, "2.0");

        Element from = doc.createElementNS(EB_NS, "eb:From");
        Element fromPartyId = doc.createElementNS(EB_NS, EB_PARTY_ID);
        if (message.from().partyIdType() != null) {
            fromPartyId.setAttributeNS(EB_NS, "eb:type", message.from().partyIdType());
        }
        fromPartyId.setTextContent(message.from().partyId());
        from.appendChild(fromPartyId);
        header.appendChild(from);

        Element to = doc.createElementNS(EB_NS, "eb:To");
        Element toPartyId = doc.createElementNS(EB_NS, EB_PARTY_ID);
        if (message.to().partyIdType() != null) {
            toPartyId.setAttributeNS(EB_NS, "eb:type", message.to().partyIdType());
        }
        toPartyId.setTextContent(message.to().partyId());
        to.appendChild(toPartyId);
        header.appendChild(to);

        appendText(doc, header, EB_NS, "eb:CPAId", message.cpaId());
        appendText(doc, header, EB_NS, "eb:ConversationId", message.conversationId());
        appendText(doc, header, EB_NS, "eb:Service", message.service());
        appendText(doc, header, EB_NS, "eb:Action", message.action());

        Element messageData = doc.createElementNS(EB_NS, "eb:MessageData");
        appendText(doc, messageData, EB_NS, "eb:MessageId", message.messageId());
        appendText(doc, messageData, EB_NS, "eb:Timestamp",
                DateTimeFormatter.ISO_INSTANT.format(message.timestamp()));
        if (message.refToMessageId() != null) {
            appendText(doc, messageData, EB_NS, "eb:RefToMessageId", message.refToMessageId());
        }
        header.appendChild(messageData);

        return header;
    }

    private Element buildAcknowledgmentElement(Document doc, EbmsMessage message) {
        Element ack = doc.createElementNS(EB_NS, "eb:Acknowledgment");
        ack.setAttributeNS(SOAP_NS, ATTR_MUST_UNDERSTAND, "1");
        ack.setAttributeNS(EB_NS, ATTR_EB_VERSION, "2.0");
        appendText(doc, ack, EB_NS, "eb:Timestamp",
                DateTimeFormatter.ISO_INSTANT.format(message.timestamp()));
        appendText(doc, ack, EB_NS, "eb:RefToMessageId", message.refToMessageId());
        Element from = doc.createElementNS(EB_NS, "eb:From");
        Element fromPartyId = doc.createElementNS(EB_NS, EB_PARTY_ID);
        fromPartyId.setTextContent(message.from().partyId());
        from.appendChild(fromPartyId);
        ack.appendChild(from);
        return ack;
    }

    private Element buildManifest(Document doc, EbmsMessage message) {
        Element manifest = doc.createElementNS(EB_NS, "eb:Manifest");
        manifest.setAttributeNS(EB_NS, ATTR_EB_VERSION, "2.0");
        for (Payload payload : message.payloads()) {
            Element ref = doc.createElementNS(EB_NS, "eb:Reference");
            ref.setAttributeNS(XMLNS_NS, "xmlns:xlink", XLINK_NS);
            ref.setAttributeNS(XLINK_NS, "xlink:href", "cid:" + payload.contentId());
            ref.setAttributeNS(XLINK_NS, "xlink:type", "simple");
            manifest.appendChild(ref);
        }
        return manifest;
    }

    private SerializedMessage buildMultipart(String soapXml, List<Payload> payloads)
            throws MessagingException, IOException {
        MimeMultipart multipart = new MimeMultipart("related; type=\"text/xml\"");

        MimeBodyPart soapPart = new MimeBodyPart();
        soapPart.setContent(soapXml, "text/xml; charset=UTF-8");
        soapPart.setHeader("Content-Id", "<rootpart@ebms.dev>");
        soapPart.setHeader("Content-Transfer-Encoding", "8bit");
        multipart.addBodyPart(soapPart);

        for (Payload payload : payloads) {
            MimeBodyPart payloadPart = new MimeBodyPart();
            payloadPart.setDataHandler(new DataHandler(
                    new ByteArrayDataSource(payload.content(), payload.mimeType())));
            payloadPart.setHeader("Content-Id", "<" + payload.contentId() + ">");
            payloadPart.setHeader("Content-Transfer-Encoding", "binary");
            multipart.addBodyPart(payloadPart);
        }

        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        mimeMessage.setContent(multipart);
        mimeMessage.saveChanges();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mimeMessage.writeTo(baos);

        String contentType = mimeMessage.getContentType();
        return new SerializedMessage(baos.toByteArray(), contentType);
    }

    @Override
    public SerializedMessage serializeError(EbmsMessage context, String errorCode, String description) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

            Element envelope = doc.createElementNS(SOAP_NS, "SOAP-ENV:Envelope");
            envelope.setAttributeNS(XMLNS_NS, "xmlns:SOAP-ENV", SOAP_NS);
            envelope.setAttributeNS(XMLNS_NS, "xmlns:eb", EB_NS);
            doc.appendChild(envelope);

            Element header = doc.createElementNS(SOAP_NS, "SOAP-ENV:Header");
            envelope.appendChild(header);
            header.appendChild(buildErrorMessageHeader(doc, context));
            header.appendChild(buildErrorList(doc, errorCode, description));

            envelope.appendChild(doc.createElementNS(SOAP_NS, "SOAP-ENV:Body"));

            if (signatureService.isEnabled()) {
                signatureService.sign(doc);
            }
            String xml = toXmlString(doc);
            return new SerializedMessage(xml.getBytes(), "text/xml; charset=UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize ebMS error", e);
        }
    }

    private Element buildErrorMessageHeader(Document doc, EbmsMessage context) {
        Element msgHeader = doc.createElementNS(EB_NS, "eb:MessageHeader");
        msgHeader.setAttributeNS(SOAP_NS, ATTR_MUST_UNDERSTAND, "1");
        msgHeader.setAttributeNS(EB_NS, ATTR_EB_VERSION, "2.0");

        String fromPartyId = context != null ? context.to().partyId() : "unknown";
        String toPartyId = context != null ? context.from().partyId() : "unknown";

        Element from = doc.createElementNS(EB_NS, "eb:From");
        Element fromPartyEl = doc.createElementNS(EB_NS, EB_PARTY_ID);
        fromPartyEl.setTextContent(fromPartyId);
        from.appendChild(fromPartyEl);
        msgHeader.appendChild(from);

        Element to = doc.createElementNS(EB_NS, "eb:To");
        Element toPartyEl = doc.createElementNS(EB_NS, EB_PARTY_ID);
        toPartyEl.setTextContent(toPartyId);
        to.appendChild(toPartyEl);
        msgHeader.appendChild(to);

        appendText(doc, msgHeader, EB_NS, "eb:CPAId", context != null ? context.cpaId() : "unknown");
        appendText(doc, msgHeader, EB_NS, "eb:ConversationId",
                context != null ? context.conversationId() : UUID.randomUUID().toString());
        appendText(doc, msgHeader, EB_NS, "eb:Service", "urn:oasis:names:tc:ebxml-msg:service");
        appendText(doc, msgHeader, EB_NS, "eb:Action", "MessageError");

        Element messageData = doc.createElementNS(EB_NS, "eb:MessageData");
        appendText(doc, messageData, EB_NS, "eb:MessageId", UUID.randomUUID() + "@ebms.dev");
        appendText(doc, messageData, EB_NS, "eb:Timestamp",
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        if (context != null) {
            appendText(doc, messageData, EB_NS, "eb:RefToMessageId", context.messageId());
        }
        msgHeader.appendChild(messageData);

        return msgHeader;
    }

    private Element buildErrorList(Document doc, String errorCode, String description) {
        Element errorList = doc.createElementNS(EB_NS, "eb:ErrorList");
        errorList.setAttributeNS(SOAP_NS, ATTR_MUST_UNDERSTAND, "1");
        errorList.setAttributeNS(EB_NS, ATTR_EB_VERSION, "2.0");
        errorList.setAttributeNS(EB_NS, "eb:highestSeverity", "Error");

        Element error = doc.createElementNS(EB_NS, "eb:Error");
        error.setAttributeNS(EB_NS, "eb:errorCode", errorCode);
        error.setAttributeNS(EB_NS, "eb:severity", "Error");

        Element desc = doc.createElementNS(EB_NS, "eb:Description");
        desc.setAttributeNS(XML_NS, "xml:lang", "en");
        desc.setTextContent(description);
        error.appendChild(desc);

        errorList.appendChild(error);
        return errorList;
    }

    private void appendText(Document doc, Element parent, String ns, String qName, String value) {
        Element el = doc.createElementNS(ns, qName);
        el.setTextContent(value);
        parent.appendChild(el);
    }

    private String toXmlString(Document doc) throws TransformerException {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }
}
