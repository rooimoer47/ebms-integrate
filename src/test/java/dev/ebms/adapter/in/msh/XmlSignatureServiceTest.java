package dev.ebms.adapter.in.msh;

import dev.ebms.domain.exception.MessageParseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlSignatureServiceTest {

    static XmlSignatureService service;

    @BeforeAll
    static void loadKeystore() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = XmlSignatureServiceTest.class.getResourceAsStream("/test-keystore.p12")) {
            ks.load(is, "testpassword".toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("ebms-test", "testpassword".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("ebms-test");
        service = new XmlSignatureService(key, cert);
    }

    @Test
    void sign_thenVerify_passes() throws Exception {
        Document doc = buildSoapDocument();
        service.sign(doc);
        service.verify(doc);
    }

    @Test
    void verify_unsignedDocument_doesNotThrow() throws Exception {
        Document doc = buildSoapDocument();
        service.verify(doc);
    }

    @Test
    void verify_tamperedDocument_throwsSecurityFailure() throws Exception {
        Document doc = buildSoapDocument();
        service.sign(doc);

        doc.getElementsByTagNameNS(SoapMimeParser.EB_NS, "Action").item(0)
                .setTextContent("TamperedAction");

        assertThatThrownBy(() -> service.verify(doc))
                .isInstanceOf(MessageParseException.class)
                .satisfies(e -> assertThat(((MessageParseException) e).getEbmsErrorCode())
                        .isEqualTo("SecurityFailure"));
    }

    @Test
    void sign_producesSignatureElement() throws Exception {
        Document doc = buildSoapDocument();
        service.sign(doc);

        assertThat(doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature")
                .getLength()).isEqualTo(1);
    }

    private static Document buildSoapDocument() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().newDocument();

        Element envelope = doc.createElementNS(SoapMimeParser.SOAP_NS, "SOAP-ENV:Envelope");
        envelope.setAttribute("xmlns:SOAP-ENV", SoapMimeParser.SOAP_NS);
        envelope.setAttribute("xmlns:eb", SoapMimeParser.EB_NS);
        doc.appendChild(envelope);

        Element header = doc.createElementNS(SoapMimeParser.SOAP_NS, "SOAP-ENV:Header");
        envelope.appendChild(header);

        Element msgHeader = doc.createElementNS(SoapMimeParser.EB_NS, "eb:MessageHeader");
        msgHeader.setAttribute("SOAP-ENV:mustUnderstand", "1");
        msgHeader.setAttribute("eb:version", "2.0");

        Element from = doc.createElementNS(SoapMimeParser.EB_NS, "eb:From");
        Element fromParty = doc.createElementNS(SoapMimeParser.EB_NS, "eb:PartyId");
        fromParty.setTextContent("sender");
        from.appendChild(fromParty);
        msgHeader.appendChild(from);

        Element to = doc.createElementNS(SoapMimeParser.EB_NS, "eb:To");
        Element toParty = doc.createElementNS(SoapMimeParser.EB_NS, "eb:PartyId");
        toParty.setTextContent("receiver");
        to.appendChild(toParty);
        msgHeader.appendChild(to);

        Element cpaId = doc.createElementNS(SoapMimeParser.EB_NS, "eb:CPAId");
        cpaId.setTextContent("cpa-test");
        msgHeader.appendChild(cpaId);

        Element convId = doc.createElementNS(SoapMimeParser.EB_NS, "eb:ConversationId");
        convId.setTextContent("conv-001");
        msgHeader.appendChild(convId);

        Element service = doc.createElementNS(SoapMimeParser.EB_NS, "eb:Service");
        service.setTextContent("TestService");
        msgHeader.appendChild(service);

        Element action = doc.createElementNS(SoapMimeParser.EB_NS, "eb:Action");
        action.setTextContent("TestAction");
        msgHeader.appendChild(action);

        Element msgData = doc.createElementNS(SoapMimeParser.EB_NS, "eb:MessageData");
        Element msgId = doc.createElementNS(SoapMimeParser.EB_NS, "eb:MessageId");
        msgId.setTextContent("test-msg-001@ebms.dev");
        msgData.appendChild(msgId);
        Element ts = doc.createElementNS(SoapMimeParser.EB_NS, "eb:Timestamp");
        ts.setTextContent("2025-05-01T00:00:00Z");
        msgData.appendChild(ts);
        msgHeader.appendChild(msgData);

        header.appendChild(msgHeader);

        Element body = doc.createElementNS(SoapMimeParser.SOAP_NS, "SOAP-ENV:Body");
        envelope.appendChild(body);

        return doc;
    }
}
