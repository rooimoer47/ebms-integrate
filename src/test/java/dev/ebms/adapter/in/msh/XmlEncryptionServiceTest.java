package dev.ebms.adapter.in.msh;

import dev.ebms.domain.Payload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XmlEncryptionServiceTest {

    static XmlEncryptionService service;
    static X509Certificate recipientCert;

    @BeforeAll
    static void setup() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = XmlEncryptionServiceTest.class.getResourceAsStream("/test-keystore.p12")) {
            ks.load(is, "testpassword".toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("ebms-test", "testpassword".toCharArray());
        recipientCert = (X509Certificate) ks.getCertificate("ebms-test");
        service = new XmlEncryptionService(key);
    }

    @Test
    void encryptAndDecryptPayloads_roundTrip_restoresOriginalContent() throws Exception {
        Document doc = buildMinimalSoapDoc();
        byte[] original = "Hello World payload".getBytes(StandardCharsets.UTF_8);
        Payload payload = new Payload("part-1", "application/pdf", original);

        List<Payload> encrypted = service.encryptPayloads(doc, List.of(payload), recipientCert);
        assertThat(encrypted).hasSize(1);
        assertThat(encrypted.get(0).mimeType()).isEqualTo(XmlEncryptionService.ENCRYPTED_MIME_TYPE);
        assertThat(encrypted.get(0).content()).isNotEqualTo(original);

        List<Payload> decrypted = service.decryptPayloads(doc, encrypted);
        assertThat(decrypted).hasSize(1);
        assertThat(decrypted.get(0).contentId()).isEqualTo("part-1");
        assertThat(decrypted.get(0).mimeType()).isEqualTo("application/pdf");
        assertThat(decrypted.get(0).content()).isEqualTo(original);
    }

    @Test
    void encryptPayloads_addsEncryptedKeyToSoapHeader() throws Exception {
        Document doc = buildMinimalSoapDoc();
        service.encryptPayloads(doc,
                List.of(new Payload("p", "text/plain", new byte[]{1, 2, 3})), recipientCert);

        NodeList keys = doc.getElementsByTagNameNS("http://www.w3.org/2001/04/xmlenc#", "EncryptedKey");
        assertThat(keys.getLength()).isEqualTo(1);
    }

    @Test
    void encryptPayloads_emptyList_doesNotModifyDoc() throws Exception {
        Document doc = buildMinimalSoapDoc();
        List<Payload> result = service.encryptPayloads(doc, List.of(), recipientCert);

        assertThat(result).isEmpty();
        NodeList keys = doc.getElementsByTagNameNS("http://www.w3.org/2001/04/xmlenc#", "EncryptedKey");
        assertThat(keys.getLength()).isZero();
    }

    @Test
    void decryptPayloads_noEncryptedKey_returnsPayloadsUnchanged() throws Exception {
        Document doc = buildMinimalSoapDoc();
        Payload payload = new Payload("p", "text/plain", new byte[]{1, 2, 3});

        List<Payload> result = service.decryptPayloads(doc, List.of(payload));
        assertThat(result).containsExactly(payload);
    }

    @Test
    void encryptPayloads_multiplePayloads_allDecryptedCorrectly() throws Exception {
        Document doc = buildMinimalSoapDoc();
        List<Payload> payloads = List.of(
                new Payload("a", "text/plain", "first".getBytes()),
                new Payload("b", "application/pdf", "second".getBytes()));

        List<Payload> encrypted = service.encryptPayloads(doc, payloads, recipientCert);
        List<Payload> decrypted = service.decryptPayloads(doc, encrypted);

        assertThat(decrypted).hasSize(2);
        assertThat(decrypted.get(0).content()).isEqualTo("first".getBytes());
        assertThat(decrypted.get(1).content()).isEqualTo("second".getBytes());
    }

    private Document buildMinimalSoapDoc() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().newDocument();

        Element envelope = doc.createElementNS(SoapMimeParser.SOAP_NS, "SOAP-ENV:Envelope");
        doc.appendChild(envelope);
        Element header = doc.createElementNS(SoapMimeParser.SOAP_NS, "SOAP-ENV:Header");
        envelope.appendChild(header);
        Element body = doc.createElementNS(SoapMimeParser.SOAP_NS, "SOAP-ENV:Body");
        envelope.appendChild(body);

        return doc;
    }
}
