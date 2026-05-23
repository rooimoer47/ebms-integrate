package dev.ebms;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void setupCpa() throws IOException {
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
        http.postForEntity("/api/cpas/reload", null, Void.class);
    }

    @Test
    void receive_validSoapMessage_returnsAcknowledgmentAndPersistsMessage() {
        String messageId = UUID.randomUUID() + "@partner-a.example.com";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        ResponseEntity<String> response = http.exchange(
                "/ebms/msh", HttpMethod.POST,
                new HttpEntity<>(soapEnvelope(messageId), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Acknowledgment");
        assertThat(response.getBody()).contains(messageId);

        List<?> messages = http.getForEntity("/api/messages?direction=INBOUND", List.class).getBody();
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        HttpEntity<String> entity = new HttpEntity<>(soapEnvelope(messageId), headers);

        http.exchange("/ebms/msh", HttpMethod.POST, entity, String.class);
        ResponseEntity<String> second = http.exchange("/ebms/msh", HttpMethod.POST, entity, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("Acknowledgment");

        List<?> messages = http.getForEntity("/api/messages?direction=INBOUND", List.class).getBody();
        long count = messages.stream()
                .filter(m -> messageId.equals(((Map<?, ?>) m).get("messageId")))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void receive_malformedSoap_returns400WithFault() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        ResponseEntity<String> response = http.exchange(
                "/ebms/msh", HttpMethod.POST,
                new HttpEntity<>("<not-soap/>", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Fault");
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

        ResponseEntity<Map> response = http.postForEntity("/api/messages", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("status", "SENT");

        partnerMsh.verify(postRequestedFor(urlEqualTo("/ebms/msh"))
                .withRequestBody(containing("NewOrder"))
                .withHeader("Content-Type", containing("text/xml")));
    }

    private static String soapEnvelope(String messageId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:eb="http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd">
                  <SOAP-ENV:Header>
                    <eb:MessageHeader SOAP-ENV:mustUnderstand="1" eb:version="2.0">
                      <eb:From><eb:PartyId>partner-a</eb:PartyId></eb:From>
                      <eb:To><eb:PartyId>our-company</eb:PartyId></eb:To>
                      <eb:CPAId>cpa-test</eb:CPAId>
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
                """.formatted(messageId);
    }
}
