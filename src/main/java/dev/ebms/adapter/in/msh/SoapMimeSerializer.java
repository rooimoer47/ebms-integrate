package dev.ebms.adapter.in.msh;

import dev.ebms.application.port.out.OutboundMessageSerializer;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Payload;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
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
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import static dev.ebms.adapter.in.msh.SoapMimeParser.EB_NS;
import static dev.ebms.adapter.in.msh.SoapMimeParser.SOAP_NS;

@Component
public class SoapMimeSerializer implements OutboundMessageSerializer {

    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    @Override
    public SerializedMessage serialize(EbmsMessage message) {
        try {
            String soapXml = buildSoapEnvelope(message);

            if (message.payloads().isEmpty()) {
                return new SerializedMessage(soapXml.getBytes(), "text/xml; charset=UTF-8");
            }

            return buildMultipart(soapXml, message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ebMS message", e);
        }
    }

    private String buildSoapEnvelope(EbmsMessage message) throws ParserConfigurationException, TransformerException {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

        Element envelope = doc.createElementNS(SOAP_NS, "SOAP-ENV:Envelope");
        envelope.setAttribute("xmlns:SOAP-ENV", SOAP_NS);
        envelope.setAttribute("xmlns:eb", EB_NS);
        doc.appendChild(envelope);

        Element header = doc.createElementNS(SOAP_NS, "SOAP-ENV:Header");
        envelope.appendChild(header);

        header.appendChild(buildMessageHeader(doc, message));

        if (message.ackRequested()) {
            Element ackRequested = doc.createElementNS(EB_NS, "eb:AckRequested");
            ackRequested.setAttribute("SOAP-ENV:mustUnderstand", "1");
            ackRequested.setAttribute("eb:version", "2.0");
            ackRequested.setAttribute("eb:signed", "false");
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

        return toXmlString(doc);
    }

    private Element buildMessageHeader(Document doc, EbmsMessage message) {
        Element header = doc.createElementNS(EB_NS, "eb:MessageHeader");
        header.setAttribute("SOAP-ENV:mustUnderstand", "1");
        header.setAttribute("eb:version", "2.0");

        Element from = doc.createElementNS(EB_NS, "eb:From");
        Element fromPartyId = doc.createElementNS(EB_NS, "eb:PartyId");
        if (message.from().partyIdType() != null) {
            fromPartyId.setAttribute("eb:type", message.from().partyIdType());
        }
        fromPartyId.setTextContent(message.from().partyId());
        from.appendChild(fromPartyId);
        header.appendChild(from);

        Element to = doc.createElementNS(EB_NS, "eb:To");
        Element toPartyId = doc.createElementNS(EB_NS, "eb:PartyId");
        if (message.to().partyIdType() != null) {
            toPartyId.setAttribute("eb:type", message.to().partyIdType());
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
        ack.setAttribute("SOAP-ENV:mustUnderstand", "1");
        ack.setAttribute("eb:version", "2.0");
        appendText(doc, ack, EB_NS, "eb:Timestamp",
                DateTimeFormatter.ISO_INSTANT.format(message.timestamp()));
        appendText(doc, ack, EB_NS, "eb:RefToMessageId", message.refToMessageId());
        Element from = doc.createElementNS(EB_NS, "eb:From");
        Element fromPartyId = doc.createElementNS(EB_NS, "eb:PartyId");
        fromPartyId.setTextContent(message.from().partyId());
        from.appendChild(fromPartyId);
        ack.appendChild(from);
        return ack;
    }

    private Element buildManifest(Document doc, EbmsMessage message) {
        Element manifest = doc.createElementNS(EB_NS, "eb:Manifest");
        manifest.setAttribute("eb:version", "2.0");
        for (Payload payload : message.payloads()) {
            Element ref = doc.createElementNS(EB_NS, "eb:Reference");
            ref.setAttribute("xmlns:xlink", XLINK_NS);
            ref.setAttribute("xlink:href", "cid:" + payload.contentId());
            ref.setAttribute("xlink:type", "simple");
            manifest.appendChild(ref);
        }
        return manifest;
    }

    private SerializedMessage buildMultipart(String soapXml, EbmsMessage message)
            throws MessagingException, IOException {
        MimeMultipart multipart = new MimeMultipart("related; type=\"text/xml\"");

        MimeBodyPart soapPart = new MimeBodyPart();
        soapPart.setContent(soapXml, "text/xml; charset=UTF-8");
        soapPart.setHeader("Content-Id", "<rootpart@ebms.dev>");
        soapPart.setHeader("Content-Transfer-Encoding", "8bit");
        multipart.addBodyPart(soapPart);

        for (Payload payload : message.payloads()) {
            MimeBodyPart payloadPart = new MimeBodyPart();
            payloadPart.setContent(payload.content(), payload.mimeType());
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
