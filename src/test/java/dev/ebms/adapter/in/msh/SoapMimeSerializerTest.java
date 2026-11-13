package dev.ebms.adapter.in.msh;

import dev.ebms.application.port.out.OutboundMessageSerializer.SerializedMessage;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Party;
import dev.ebms.domain.Payload;
import org.junit.jupiter.api.BeforeAll;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SoapMimeSerializerTest {

    private final SoapMimeSerializer serializer = new SoapMimeSerializer(
            XmlSignatureService.disabled(), XmlEncryptionService.disabled());

    static XmlSignatureService signingService;

    @BeforeAll
    static void loadKeystore() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = SoapMimeSerializerTest.class.getResourceAsStream("/test-keystore.p12")) {
            ks.load(is, "testpassword".toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("ebms-test", "testpassword".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("ebms-test");
        signingService = new XmlSignatureService(key, cert);
    }

    @Test
    void serialize_ackMessage_producesTextXmlWithAcknowledgmentElement() throws Exception {
        EbmsMessage ack = EbmsMessage.newAck(
                "ack-001@ebms.dev", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "orig-msg-001@partner-a.example.com");

        SerializedMessage result = serializer.serialize(ack, null);

        assertThat(result.contentType()).startsWith("text/xml");

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        XPath xpath = buildXpath();

        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:Acknowledgment/eb:Timestamp", doc))
                .isNotBlank();
        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:Acknowledgment/eb:RefToMessageId", doc))
                .isEqualTo("orig-msg-001@partner-a.example.com");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:MessageData/eb:MessageId", doc))
                .isEqualTo("ack-001@ebms.dev");
    }

    @Test
    void serialize_ackMessage_doesNotIncludeManifest() {
        EbmsMessage ack = EbmsMessage.newAck(
                "ack-002@ebms.dev", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "orig-002@partner-a.example.com");

        SerializedMessage result = serializer.serialize(ack, null);

        assertThat(new String(result.body(), StandardCharsets.UTF_8)).doesNotContain("Manifest");
    }

    @Test
    void serialize_messageWithoutPayloads_producesTextXmlWithCorrectHeader() throws Exception {
        EbmsMessage message = EbmsMessage.newOutbound(
                "msg-001@test", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder", List.of(), false);

        SerializedMessage result = serializer.serialize(message, null);

        assertThat(result.contentType()).startsWith("text/xml");

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        XPath xpath = buildXpath();

        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:MessageData/eb:MessageId", doc))
                .isEqualTo("msg-001@test");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:Action", doc))
                .isEqualTo("NewOrder");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:Service", doc))
                .isEqualTo("OrderService");
    }

    @Test
    void serialize_messageWithAckRequested_includesAckRequestedElement() throws Exception {
        EbmsMessage message = EbmsMessage.newOutbound(
                "msg-003@test", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder", List.of(), true);

        SerializedMessage result = serializer.serialize(message, null);

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        XPath xpath = buildXpath();

        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:AckRequested/@eb:version", doc))
                .isEqualTo("2.0");
    }

    @Test
    void serialize_messageWithPayloads_producesMultipartRelated() {
        byte[] payloadContent = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        EbmsMessage message = EbmsMessage.newOutbound(
                "msg-002@test", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder",
                List.of(new Payload("doc-001@test", "text/plain", payloadContent)), false);

        SerializedMessage result = serializer.serialize(message, null);

        assertThat(result.contentType()).containsIgnoringCase("multipart/related");
        String bodyStr = new String(result.body(), StandardCharsets.UTF_8);
        assertThat(bodyStr).contains("cid:doc-001@test").contains("Hello, World!");
    }

    @Test
    void serializeError_withContext_includesErrorListAndRefToMessageId() throws Exception {
        EbmsMessage context = EbmsMessage.newInbound(
                "orig-001@partner-a.example.com", "conv-001", "cpa-001",
                Party.of("partner-a"), Party.of("our-company"),
                "OrderService", "NewOrder",
                Instant.now(), List.of(), false, null);

        SerializedMessage result = serializer.serializeError(context, "ValueNotRecognized", "CPA not found");

        assertThat(result.contentType()).startsWith("text/xml");
        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        XPath xpath = buildXpath();

        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:ErrorList/@eb:highestSeverity", doc))
                .isEqualTo("Error");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:ErrorList/eb:Error/@eb:errorCode", doc))
                .isEqualTo("ValueNotRecognized");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:Action", doc))
                .isEqualTo("MessageError");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:MessageData/eb:RefToMessageId", doc))
                .isEqualTo("orig-001@partner-a.example.com");
    }

    @Test
    void serializeError_withoutContext_producesErrorListWithNoRefToMessageId() throws Exception {
        SerializedMessage result = serializer.serializeError(null, "OtherXml", "Failed to parse XML");

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        XPath xpath = buildXpath();

        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:ErrorList/eb:Error/@eb:errorCode", doc))
                .isEqualTo("OtherXml");
        assertThat(xpath.evaluate("/soap:Envelope/soap:Header/eb:MessageHeader/eb:MessageData/eb:RefToMessageId", doc))
                .isBlank();
    }

    @Test
    void serialize_withSigningEnabled_includesSignatureElement() throws Exception {
        SoapMimeSerializer signingSerializer = new SoapMimeSerializer(
                signingService, XmlEncryptionService.disabled());
        EbmsMessage message = EbmsMessage.newOutbound(
                "msg-signed@test", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder", List.of(), false);

        SerializedMessage result = signingSerializer.serialize(message, null);

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        assertThat(doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature")
                .getLength()).isEqualTo(1);
    }

    @Test
    void serialize_partiesWithRoles_writesRoleAfterPartyId() throws Exception {
        EbmsMessage message = EbmsMessage.newOutbound(
                "msg-roles@test", "conv-001", "cpa-001",
                new Party("urn:osb:oin:00000001822477348001", "urn:osb:oin", "Bronhouder"),
                new Party("urn:osb:oin:00000001810220350000", "urn:osb:oin", "Afnemer"),
                "urn:osb:services:verstrekking", "verstrekkingDoorLV", List.of(), false);

        SerializedMessage result = serializer.serialize(message, null);

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        XPath xpath = buildXpath();

        assertThat(xpath.evaluate(
                "/soap:Envelope/soap:Header/eb:MessageHeader/eb:From/eb:Role", doc))
                .isEqualTo("Bronhouder");
        assertThat(xpath.evaluate(
                "/soap:Envelope/soap:Header/eb:MessageHeader/eb:To/eb:Role", doc))
                .isEqualTo("Afnemer");

        // ebMS 2.0 requires Role to follow the PartyId elements within From / To.
        assertThat(xpath.evaluate(
                "local-name(/soap:Envelope/soap:Header/eb:MessageHeader/eb:From/*[1])", doc))
                .isEqualTo("PartyId");
        assertThat(xpath.evaluate(
                "local-name(/soap:Envelope/soap:Header/eb:MessageHeader/eb:From/*[2])", doc))
                .isEqualTo("Role");
    }

    @Test
    void serialize_partiesWithoutRoles_omitsRoleElementEntirely() throws Exception {
        EbmsMessage message = EbmsMessage.newOutbound(
                "msg-noroles@test", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder", List.of(), false);

        SerializedMessage result = serializer.serialize(message, null);

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        assertThat(doc.getElementsByTagNameNS(SoapMimeParser.EB_NS, "Role").getLength())
                .isZero();
    }

    @Test
    void serialize_blankRole_omitsRoleElement() throws Exception {
        EbmsMessage message = EbmsMessage.newOutbound(
                "msg-blankrole@test", "conv-001", "cpa-001",
                new Party("our-company", null, "   "),
                new Party("partner-a", null, ""),
                "OrderService", "NewOrder", List.of(), false);

        SerializedMessage result = serializer.serialize(message, null);

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        assertThat(doc.getElementsByTagNameNS(SoapMimeParser.EB_NS, "Role").getLength())
                .isZero();
    }

    @Test
    void serializeThenParse_rolesSurviveTheRoundTrip() throws Exception {
        EbmsMessage sent = EbmsMessage.newOutbound(
                "msg-roundtrip@test", "conv-001", "cpa-001",
                new Party("urn:osb:oin:00000001822477348001", "urn:osb:oin", "Bronhouder"),
                new Party("urn:osb:oin:00000001810220350000", "urn:osb:oin", "Afnemer"),
                "urn:osb:services:verstrekking", "verstrekkingDoorLV", List.of(), false);

        SerializedMessage wire = serializer.serialize(sent, null);
        SoapMimeParser parser = new SoapMimeParser(
                XmlSignatureService.disabled(), XmlEncryptionService.disabled());

        EbmsMessage received = parser.parse(wire.body(), wire.contentType());

        assertThat(received.from().role()).isEqualTo("Bronhouder");
        assertThat(received.to().role()).isEqualTo("Afnemer");
        assertThat(received.from().partyIdType()).isEqualTo("urn:osb:oin");
    }

    @Test
    void serializeError_contextWithRoles_swapsAndKeepsRoles() throws Exception {
        EbmsMessage inbound = EbmsMessage.newInbound(
                "msg-err@test", "conv-001", "cpa-001",
                new Party("partner-a", null, "Afnemer"),
                new Party("our-company", null, "Bronhouder"),
                "OrderService", "NewOrder", Instant.now(), List.of(), false, null);

        SerializedMessage result = serializer.serializeError(inbound, "ValueNotRecognized", "bad cpa");

        Document doc = parseXml(new String(result.body(), StandardCharsets.UTF_8));
        XPath xpath = buildXpath();

        // The error is sent back the other way, so our role becomes the From role.
        assertThat(xpath.evaluate(
                "/soap:Envelope/soap:Header/eb:MessageHeader/eb:From/eb:Role", doc))
                .isEqualTo("Bronhouder");
        assertThat(xpath.evaluate(
                "/soap:Envelope/soap:Header/eb:MessageHeader/eb:To/eb:Role", doc))
                .isEqualTo("Afnemer");
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private XPath buildXpath() {
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return switch (prefix) {
                    case "soap" -> SoapMimeParser.SOAP_NS;
                    case "eb" -> SoapMimeParser.EB_NS;
                    default -> XMLConstants.NULL_NS_URI;
                };
            }
            @Override public String getPrefix(String ns) { return null; }
            @Override public Iterator<String> getPrefixes(String ns) { return null; }
        });
        return xpath;
    }
}
