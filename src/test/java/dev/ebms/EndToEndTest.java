package dev.ebms;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndTest {

    static final EmbeddedPostgres POSTGRES;
    static final Path CPA_DIR;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            CPA_DIR = Files.createTempDirectory("ebms-cpa-e2e");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @RegisterExtension
    static WireMockExtension partnerMsh = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("ebms.cpa-directory", CPA_DIR::toString);
    }

    @LocalServerPort
    int port;

    RestClient http;

    @BeforeEach
    void setupCpa() throws IOException {
        // Spring Boot 4 removed TestRestTemplate. RestClient throws on 4xx/5xx by default,
        // so error statuses are passed through for the assertions to inspect.
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        partnerMsh.stubFor(post(urlEqualTo("/ebms/msh"))
                .willReturn(ok()
                        .withHeader("Content-Type", "text/xml; charset=UTF-8")
                        .withBody("<ack/>")));

        Files.writeString(CPA_DIR.resolve("cpa-test.yml"), """
                cpaId: "cpa-test"
                fromParty:
                  partyId: "our-company"
                  partyIdType: "urn:example:partyIdType"
                toParty:
                  partyId: "partner-a"
                  partyIdType: "urn:example:partyIdType"
                transportUrl: "%s/ebms/msh"
                ackRequested: true
                duplicateElimination: true
                retries: 3
                retryIntervalSeconds: 60
                """.formatted(partnerMsh.baseUrl()));
        http.post().uri("/api/cpas/reload").retrieve().toBodilessEntity();
    }

    @Test
    void receive_validSoapMessage_returnsAcknowledgmentAndPersistsMessage() {
        String messageId = UUID.randomUUID() + "@partner-a.example.com";

        ResponseEntity<String> response = postSoap(soapEnvelope(messageId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Acknowledgment");
        assertThat(response.getBody()).contains(messageId);

        List<?> messages = http.get().uri("/api/messages?direction=INBOUND").retrieve().body(List.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) messages.stream()
                .map(m -> (Map<?, ?>) m)
                .filter(m -> messageId.equals(m.get("messageId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Message not found"));
        assertThat(stored)
                .containsEntry("status", "RECEIVED")
                .containsEntry("action", "NewOrder")
                .containsEntry("fromPartyId", "partner-a");
    }

    @Test
    void receive_duplicateMessage_returnsAcknowledgmentAndStoresOnlyOnce() {
        String messageId = UUID.randomUUID() + "@partner-a.example.com";
        String envelope = soapEnvelope(messageId);

        postSoap(envelope);
        ResponseEntity<String> second = postSoap(envelope);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("Acknowledgment");

        List<?> messages = http.get().uri("/api/messages?direction=INBOUND").retrieve().body(List.class);
        long count = messages.stream()
                .filter(m -> messageId.equals(((Map<?, ?>) m).get("messageId")))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void receive_malformedSoap_returns400WithErrorList() {
        ResponseEntity<String> response = postSoap("<not-soap/>");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("ErrorList");
        assertThat(response.getBody()).contains("Inconsistent");
    }

    @Test
    void receive_unknownCpa_returns400WithErrorList() {
        ResponseEntity<String> response =
                postSoap(soapEnvelope(UUID.randomUUID() + "@partner-a.example.com", "cpa-unknown"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("ErrorList");
        assertThat(response.getBody()).contains("ValueNotRecognized");
    }

    @Test
    void send_validRequest_deliversSoapToPartnerAndStatusIsSent() {
        Map<String, Object> request = Map.of(
                "cpaId", "cpa-test",
                "conversationId", UUID.randomUUID().toString(),
                "service", "OrderService",
                "action", "NewOrder",
                "payloads", List.of()
        );

        ResponseEntity<Map> response = postJson(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("status", "SENT");

        partnerMsh.verify(postRequestedFor(urlEqualTo("/ebms/msh"))
                .withRequestBody(containing("NewOrder"))
                .withHeader("Content-Type", containing("text/xml")));
    }

    @Test
    void receive_acknowledgment_transitionsOutboundMessageToAcked() {
        Map<String, Object> request = Map.of(
                "cpaId", "cpa-test",
                "conversationId", UUID.randomUUID().toString(),
                "service", "OrderService",
                "action", "NewOrder",
                "payloads", List.of()
        );
        ResponseEntity<Map> sendResponse = postJson(request);
        assertThat(sendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = sendResponse.getBody();
        assertThat(sent).containsEntry("status", "SENT");
        String sentMessageId = (String) sent.get("messageId");
        String sentId = (String) sent.get("id");

        ResponseEntity<String> ackResponse = postSoap(acknowledgmentEnvelope(sentMessageId));

        assertThat(ackResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> updated = http.get().uri("/api/messages/" + sentId).retrieve().body(Map.class);
        assertThat(updated).containsEntry("status", "ACKED");
    }

    // ---------- P1-4: the v19 fetch endpoint jms-producer calls ----------

    @Test
    void v19GetMessage_returnsTheShapeTheGeneratedClientModelExpects() {
        String messageId = UUID.randomUUID() + "@partner-a.example.com";
        postSoap(soapEnvelope(messageId));

        Map<String, Object> props = v19Properties(messageId, true);

        assertThat(props)
                .containsEntry("cpaId", "cpa-test")
                .containsEntry("messageId", messageId)
                .containsEntry("conversationId", "conv-e2e-001")
                .containsEntry("service", "OrderService")
                .containsEntry("action", "NewOrder");
        assertThat(asMap(props.get("fromParty"))).containsEntry("partyId", "partner-a");
        assertThat(asMap(props.get("toParty"))).containsEntry("partyId", "our-company");

        // Every property jms-producer's MessageFactory reads has to be present.
        assertThat(props).containsKeys("cpaId", "conversationId", "messageId", "service",
                "action", "fromParty", "toParty", "messageStatus", "timestamp");
    }

    @Test
    void v19GetMessage_writesStatusAsAStringAndTimestampAsIso() {
        // ebms-core 2.20.x models MessageStatus as an object; the clients are generated from
        // the v18 copy where it is a bare enum, and theirs is the one that deserialises.
        String messageId = UUID.randomUUID() + "@partner-a.example.com";
        postSoap(soapEnvelope(messageId));

        Map<String, Object> props = v19Properties(messageId, true);

        assertThat(props.get("messageStatus")).isInstanceOf(String.class);
        assertThat((String) props.get("timestamp")).matches("\\d{4}-\\d{2}-\\d{2}T.*");
    }

    @Test
    void v19GetMessage_writesPayloadContentAsBase64() {
        // Submitted rather than received, because it is the only route here that carries a
        // payload -- the inbound fixture has an empty SOAP body. jms-producer reads
        // dataSources.get(0).getContent(), so the base64 round trip is load-bearing.
        String content = "<order><line>1</line></order>";
        String messageId = submitWithPayload(content);

        List<Map<String, Object>> dataSources = v19DataSources(messageId);

        assertThat(dataSources).hasSize(1);
        assertThat(dataSources.get(0))
                .containsEntry("contentId", "order-1")
                .containsEntry("name", "order-1")
                .containsEntry("contentType", "text/xml; charset=UTF-8");
        assertThat(new String(Base64.getDecoder()
                .decode((String) dataSources.get(0).get("content")), StandardCharsets.UTF_8))
                .isEqualTo(content);
    }

    @Test
    void v19GetMessage_outboundAwaitingAck_isReportedAsCreatedNotDelivered() {
        // The CPA requests an acknowledgement and the stub partner answers with something that
        // is not one, so the message sits at SENT -- reported as CREATED, because the ack is
        // the proof of delivery.
        String messageId = submitWithPayload("<order/>");

        assertThat(v19Properties(messageId, false)).containsEntry("messageStatus", "CREATED");
    }

    @Test
    void v19GetMessage_processTrue_reportsProcessed() {
        String messageId = UUID.randomUUID() + "@partner-a.example.com";
        postSoap(soapEnvelope(messageId));

        assertThat(v19Properties(messageId, true)).containsEntry("messageStatus", "PROCESSED");

        // A second fetch still returns it -- a redelivered RECEIVED event must not 404.
        assertThat(v19Get(messageId, true, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void v19GetMessage_processFalse_leavesTheMessageUnprocessed() {
        String messageId = UUID.randomUUID() + "@partner-a.example.com";
        postSoap(soapEnvelope(messageId));

        assertThat(v19Properties(messageId, false)).containsEntry("messageStatus", "RECEIVED");
        assertThat(v19Properties(messageId, false)).containsEntry("messageStatus", "RECEIVED");
    }

    @Test
    void v19GetMessage_urlEncodedMessageId_resolvesToTheSameMessage() {
        // jms-producer URL-encodes the id, so the '@' of the Message-ID arrives as %40.
        String messageId = UUID.randomUUID() + "@partner-a.example.com";
        postSoap(soapEnvelope(messageId));

        assertThat(v19Get(messageId, false, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(v19Properties(messageId, false)).containsEntry("messageId", messageId);
    }

    @Test
    void v19GetMessage_messageWithNoPayloads_stillReportsAnEmptyDataSourcesArray() {
        String messageId = UUID.randomUUID() + "@partner-a.example.com";
        postSoap(soapEnvelope(messageId));

        assertThat(v19DataSources(messageId)).isEmpty();
    }

    @Test
    void v19GetMessage_unknownId_is404() {
        assertThat(v19Get("no-such-message@nowhere.example.com", false, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Mirrors how jms-producer builds the call: encoded path segment plus a process flag. */
    private <T> ResponseEntity<T> v19Get(String messageId, boolean process, Class<T> type) {
        return http.get()
                .uri(builder -> builder
                        .path("/service/rest/v19/ebms/messages/{messageId}")
                        .queryParam("process", process)
                        .build(messageId))
                .retrieve()
                .toEntity(type);
    }

    private Map<String, Object> v19Properties(String messageId, boolean process) {
        return asMap(asMap(v19Get(messageId, process, Map.class).getBody()).get("properties"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> v19DataSources(String messageId) {
        return (List<Map<String, Object>>) asMap(
                v19Get(messageId, false, Map.class).getBody()).get("dataSources");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private String submitWithPayload(String content) {
        Map<String, Object> request = Map.of(
                "cpaId", "cpa-test",
                "conversationId", UUID.randomUUID().toString(),
                "service", "OrderService",
                "action", "NewOrder",
                "payloads", List.of(Map.of(
                        "contentId", "order-1",
                        "mimeType", "text/xml; charset=UTF-8",
                        "content", Base64.getEncoder()
                                .encodeToString(content.getBytes(StandardCharsets.UTF_8))))
        );
        return (String) asMap(postJson(request).getBody()).get("messageId");
    }

    private ResponseEntity<String> postSoap(String envelope) {
        return http.post()
                .uri("/ebms/msh")
                .contentType(MediaType.TEXT_XML)
                .body(envelope)
                .retrieve()
                .toEntity(String.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> postJson(Map<String, Object> request) {
        return http.post()
                .uri("/api/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(Map.class);
    }

    private static String acknowledgmentEnvelope(String refToMessageId) {
        String ackMessageId = UUID.randomUUID() + "@partner-a.example.com";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:eb="http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd">
                  <SOAP-ENV:Header>
                    <eb:MessageHeader SOAP-ENV:mustUnderstand="1" eb:version="2.0">
                      <eb:From><eb:PartyId>partner-a</eb:PartyId></eb:From>
                      <eb:To><eb:PartyId>our-company</eb:PartyId></eb:To>
                      <eb:CPAId>cpa-test</eb:CPAId>
                      <eb:ConversationId>conv-ack-001</eb:ConversationId>
                      <eb:Service>urn:oasis:names:tc:ebxml-msg:service</eb:Service>
                      <eb:Action>Acknowledgment</eb:Action>
                      <eb:MessageData>
                        <eb:MessageId>%s</eb:MessageId>
                        <eb:Timestamp>%s</eb:Timestamp>
                        <eb:RefToMessageId>%s</eb:RefToMessageId>
                      </eb:MessageData>
                    </eb:MessageHeader>
                    <eb:Acknowledgment SOAP-ENV:mustUnderstand="1" eb:version="2.0">
                      <eb:Timestamp>%s</eb:Timestamp>
                      <eb:RefToMessageId>%s</eb:RefToMessageId>
                      <eb:From><eb:PartyId>partner-a</eb:PartyId></eb:From>
                    </eb:Acknowledgment>
                  </SOAP-ENV:Header>
                  <SOAP-ENV:Body/>
                </SOAP-ENV:Envelope>
                """.formatted(ackMessageId, java.time.Instant.now(), refToMessageId,
                java.time.Instant.now(), refToMessageId);
    }

    private static String soapEnvelope(String messageId) {
        return soapEnvelope(messageId, "cpa-test");
    }

    private static String soapEnvelope(String messageId, String cpaId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:eb="http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd">
                  <SOAP-ENV:Header>
                    <eb:MessageHeader SOAP-ENV:mustUnderstand="1" eb:version="2.0">
                      <eb:From><eb:PartyId>partner-a</eb:PartyId></eb:From>
                      <eb:To><eb:PartyId>our-company</eb:PartyId></eb:To>
                      <eb:CPAId>%s</eb:CPAId>
                      <eb:ConversationId>conv-e2e-001</eb:ConversationId>
                      <eb:Service>OrderService</eb:Service>
                      <eb:Action>NewOrder</eb:Action>
                      <eb:MessageData>
                        <eb:MessageId>%s</eb:MessageId>
                        <eb:Timestamp>2025-05-18T12:00:00Z</eb:Timestamp>
                      </eb:MessageData>
                    </eb:MessageHeader>
                    <eb:AckRequested SOAP-ENV:mustUnderstand="1" eb:version="2.0" eb:signed="false"/>
                  </SOAP-ENV:Header>
                  <SOAP-ENV:Body/>
                </SOAP-ENV:Envelope>
                """.formatted(cpaId, messageId);
    }
}
