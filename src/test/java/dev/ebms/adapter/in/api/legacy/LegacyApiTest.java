package dev.ebms.adapter.in.api.legacy;

import dev.ebms.adapter.in.api.legacy.CpaServiceDtos.EbmsDeliveryLogEntry;
import dev.ebms.adapter.in.api.legacy.CpaServiceDtos.EbmsMessage;
import dev.ebms.adapter.in.api.legacy.CpaServiceDtos.EbmsMessageFilterDto;
import dev.ebms.adapter.in.api.legacy.CpaServiceDtos.PaginatedResponse;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * The five paths absorbed from ebms-api-service, through the real HTTP stack and Postgres, read
 * back the way their callers read them: cpa-service's own DTOs for the console paths, and
 * jms-consumer's {@code Map<String, Integer>} for the failure counts.
 *
 * <p>Responses are parsed strictly -- unknown properties fail -- and every object is also checked
 * to carry exactly the fields the caller's record has. A renamed field therefore fails here
 * rather than turning into a silent null in the console.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyApiTest {

    static final String USER = "ebms";
    static final String PASSWORD = "test-secret";
    static final String API_SERVICE_USER = "ebms-api";
    static final String API_SERVICE_PASSWORD = "api-secret";

    /** A real production cpaId shape: the {@code $} is what broke CPA reads in phase 2. */
    static final String NEW_CPA = "DGL-NEW-1-0$1-0_cpa-new";

    static final EmbeddedPostgres POSTGRES;
    static final Path CPA_DIR;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            CPA_DIR = Files.createTempDirectory("ebms-legacy-api");
            for (String cpaId : List.of("cpa-old", NEW_CPA)) {
                Files.writeString(CPA_DIR.resolve(cpaId.replace('$', '_') + ".yml"), """
                        cpaId: "%s"
                        fromParty:
                          partyId: "our-company"
                          partyIdType: "urn:example:partyIdType"
                        toParty:
                          partyId: "partner-a"
                          partyIdType: "urn:example:partyIdType"
                        transportUrl: "https://partner-a.example.nl/ebms/msh"
                        ackRequested: true
                        duplicateElimination: true
                        retries: 3
                        retryIntervalSeconds: 60
                        """.formatted(cpaId));
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("ebms.cpa-directory", CPA_DIR::toString);
        registry.add("ebms.security.api.username", () -> USER);
        registry.add("ebms.security.api.password", () -> PASSWORD);
        registry.add("ebms.security.api.api-service-username", () -> API_SERVICE_USER);
        registry.add("ebms.security.api.api-service-password", () -> API_SERVICE_PASSWORD);
    }

    static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    static final ZoneId ZONE = ZoneId.systemDefault();
    static final LocalDate DAY1 = LocalDate.of(2026, 11, 2);
    static final LocalDate DAY2 = LocalDate.of(2026, 11, 3);
    static final LocalDate DAY3 = LocalDate.of(2026, 11, 4);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    /** Authenticated the way cpa-service and jms-consumer are: with ebms-api-service's secret. */
    RestClient http;

    @BeforeEach
    void seed() {
        http = client(API_SERVICE_USER, API_SERVICE_PASSWORD);
        jdbc.update("DELETE FROM payloads");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM delivery_log");

        // Inbound: the bare eb:PartyId text, with its type beside it -- what SoapMimeParser stores.
        insert("in-1@partner-a", "conv-1", "cpa-old", "INBOUND", "RECEIVED", false, false,
                "00000001810220350000", "urn:osb:oin", "urn:osb:oin:00000001822477348001", null,
                null, at(DAY1, 10));
        insert("in-2@partner-a", "conv-2", "cpa-old", "INBOUND", "RECEIVED", true, false,
                "00000001810220350000", "urn:osb:oin", "urn:osb:oin:00000001822477348001", null,
                "out-1@us", at(DAY1, 11));
        // Outbound: the caller's prefixed id, as jms-consumer sends it.
        insert("out-1@us", "conv-1", "cpa-old", "OUTBOUND", "SENT", false, true,
                "urn:osb:oin:00000001822477348001", "urn:osb:oin", "urn:osb:oin:00000001810220350000",
                "urn:osb:oin", null, at(DAY2, 9));
        // An intermediary message: the afnemer's OIN is embedded in the id.
        insert("out-2@00000003342283970000", "conv-3", "cpa-old", "OUTBOUND", "FAILED", false, true,
                "urn:osb:oin:00000001822477348001", null, "urn:osb:oin:00000001810220350000", null,
                null, at(DAY2, 10));
        insert("out-3@us", "conv-4", "cpa-other", "OUTBOUND", "ACKED", false, true,
                "urn:osb:oin:00000001822477348001", "urn:osb:oin", "urn:osb:oin:00000001810220350000",
                "urn:osb:oin", null, at(DAY3, 8));
    }

    // ---------- POST /ebms/message ----------

    @Test
    void search_withoutAFilter_pagesNewestFirst() {
        PaginatedResponse<EbmsMessage> page = search(EbmsMessageFilterDto.none(), "?page=0&size=2");

        assertThat(ids(page)).containsExactly("out-3@us", "out-2@00000003342283970000");
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.currentPage()).isZero();
        assertThat(page.pageSize()).isEqualTo(2);

        PaginatedResponse<EbmsMessage> last = search(EbmsMessageFilterDto.none(), "?page=2&size=2");
        assertThat(ids(last)).containsExactly("in-1@partner-a");
    }

    @Test
    void search_answersWithEbmsCoresStatusNumbers() {
        Map<String, Short> statuses = search(EbmsMessageFilterDto.none(), "?size=50").content().stream()
                .collect(Collectors.toMap(EbmsMessage::messageId, EbmsMessage::status));

        assertThat(statuses).containsOnly(
                entry("in-1@partner-a", (short) 2),
                entry("in-2@partner-a", (short) 3),
                entry("out-1@us", (short) 10),
                entry("out-2@00000003342283970000", (short) 11),
                entry("out-3@us", (short) 12));
    }

    @Test
    void search_rendersMessagesTheWayEbmsCoreStoredThem() {
        EbmsMessage in1 = only(search(EbmsMessageFilterDto.none().withMessageId("in-1@partner-a"), ""));

        assertThat(in1.fromPartyId()).isEqualTo("urn:osb:oin:00000001810220350000");
        assertThat(in1.toPartyId()).isEqualTo("urn:osb:oin:00000001822477348001");
        assertThat(in1.timestamp()).isEqualTo(LocalDateTime.of(DAY1, java.time.LocalTime.of(10, 0)));
        assertThat(in1.statusTime()).isNotNull();
        assertThat(in1.cpaId()).isEqualTo("cpa-old");
        assertThat(in1.conversationId()).isEqualTo("conv-1");
        assertThat(in1.service()).isEqualTo("urn:osb:services:test");
        assertThat(in1.content()).isNull();
        assertThat(in1.timeToLive()).isNull();
        assertThat(in1.persistTime()).isNull();
    }

    @Test
    void search_numericPartyId_matchesBothStoredForms() {
        // Stored bare with its type (inbound) ...
        assertThat(ids(search(EbmsMessageFilterDto.none().withFromPartyId("00000001810220350000"), "")))
                .containsExactlyInAnyOrder("in-1@partner-a", "in-2@partner-a");
        // ... and stored prefixed (outbound).
        assertThat(ids(search(EbmsMessageFilterDto.none().withToPartyId("00000001810220350000"), "")))
                .containsExactlyInAnyOrder("out-1@us", "out-2@00000003342283970000", "out-3@us");
        // The prefixed form finds the bare-stored row too.
        assertThat(ids(search(EbmsMessageFilterDto.none()
                .withFromPartyId("urn:osb:oin:00000001810220350000"), "")))
                .containsExactlyInAnyOrder("in-1@partner-a", "in-2@partner-a");
    }

    @Test
    void search_byStatusNumber() {
        assertThat(ids(search(EbmsMessageFilterDto.none().withStatus(10), ""))).containsExactly("out-1@us");
        assertThat(ids(search(EbmsMessageFilterDto.none().withStatus(3), ""))).containsExactly("in-2@partner-a");
        // A status we never produce finds nothing -- not everything.
        assertThat(search(EbmsMessageFilterDto.none().withStatus(13), "").content()).isEmpty();
    }

    @Test
    void search_aBareOinAsMessageId_findsTheIntermediaryMessageThatEmbedsIt() {
        assertThat(ids(search(EbmsMessageFilterDto.none().withMessageId("00000003342283970000"), "")))
                .containsExactly("out-2@00000003342283970000");
    }

    @Test
    void search_globalSearchTerm_isAnExactMatchOnAnyUnfilteredIdField() {
        assertThat(ids(search(EbmsMessageFilterDto.none().withGlobalSearchTerm("conv-1"), "")))
                .containsExactlyInAnyOrder("in-1@partner-a", "out-1@us");
        assertThat(ids(search(EbmsMessageFilterDto.none().withGlobalSearchTerm("out-1@us"), "")))
                .containsExactlyInAnyOrder("out-1@us", "in-2@partner-a");  // message id, and a ref to it
        assertThat(search(EbmsMessageFilterDto.none().withGlobalSearchTerm("conv"), "").content()).isEmpty();
        // Combined with a filter, the term searches only the fields left open.
        assertThat(ids(search(EbmsMessageFilterDto.none().withCpaId("cpa-old").withGlobalSearchTerm("conv-1"), "")))
                .containsExactlyInAnyOrder("in-1@partner-a", "out-1@us");
    }

    @Test
    void search_dateRange_includesWholeDays() {
        assertThat(ids(search(EbmsMessageFilterDto.none().withDates(DAY2, DAY2), "")))
                .containsExactlyInAnyOrder("out-1@us", "out-2@00000003342283970000");
        assertThat(ids(search(EbmsMessageFilterDto.none().withDates(DAY1, null), "?size=50")))
                .hasSize(5);
        assertThat(ids(search(EbmsMessageFilterDto.none().withDates(null, DAY1), "")))
                .containsExactlyInAnyOrder("in-1@partner-a", "in-2@partner-a");
    }

    @Test
    void search_rejectsAPageItCannotServe() {
        assertThat(status(http.post().uri("/ebms/message?size=0").contentType(MediaType.APPLICATION_JSON)
                .body(EbmsMessageFilterDto.none()))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(http.post().uri("/ebms/message?page=-1").contentType(MediaType.APPLICATION_JSON)
                .body(EbmsMessageFilterDto.none()))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- POST /ebms/message/count ----------

    @Test
    void count_agreesWithTheSearch() {
        EbmsMessageFilterDto filter = EbmsMessageFilterDto.none().withCpaId("cpa-old");

        String body = http.post().uri("/ebms/message/count").contentType(MediaType.APPLICATION_JSON)
                .body(filter).retrieve().body(String.class);

        assertThat(STRICT.readValue(body, Long.class)).isEqualTo(4L)
                .isEqualTo(search(filter, "").totalElements());
    }

    // ---------- GET /ebms/message/deliverylog ----------

    @Test
    void deliveryLog_isTheMessagesAttemptsOldestFirst() {
        attempt("out-2@00000003342283970000", at(DAY2, 10), 2, "503 Service Unavailable");
        attempt("out-2@00000003342283970000", at(DAY2, 11), 1, null);
        attempt("out-1@us", at(DAY2, 9), 1, null);

        List<EbmsDeliveryLogEntry> log = readStrict(
                http.get().uri("/ebms/message/deliverylog?messageId={id}", "out-2@00000003342283970000")
                        .retrieve().body(String.class),
                new TypeReference<>() { }, EbmsDeliveryLogEntry.class);

        assertThat(log).extracting(EbmsDeliveryLogEntry::status).containsExactly((short) 2, (short) 1);
        assertThat(log.get(0).errorMessage()).isEqualTo("503 Service Unavailable");
        // ebms-api-service turned a null error into "".
        assertThat(log.get(1).errorMessage()).isEmpty();
        assertThat(log.get(0).uri()).isEqualTo("https://partner-a.example.nl/ebms/msh");
        assertThat(log.get(0).timestamp()).isEqualTo(LocalDateTime.of(DAY2, java.time.LocalTime.of(10, 0)));
    }

    @Test
    void deliveryLog_ofAnUnknownMessage_isEmpty_notNotFound() {
        ResponseEntity<String> response = http.get().uri("/ebms/message/deliverylog?messageId=nope")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    // ---------- PUT /ebms/message/move/from/{a}/to/{b} ----------

    @Test
    void move_repointsOnlyFailedMessages() {
        HttpStatus status = status(http.put().uri("/ebms/message/move/from/{a}/to/{b}", "cpa-old", NEW_CPA));

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForList("SELECT message_id FROM messages WHERE cpa_id = ?", String.class, NEW_CPA))
                .containsExactly("out-2@00000003342283970000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE cpa_id = 'cpa-old'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void move_toACpaWeDoNotHold_isNotFound_andMovesNothing() {
        HttpStatus status = status(http.put().uri("/ebms/message/move/from/{a}/to/{b}", "cpa-old", "no-such-cpa"));

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE cpa_id = 'cpa-old'", Integer.class))
                .isEqualTo(4);
    }

    // ---------- GET /ebms/throttle/failurecounts ----------

    /** Read exactly as jms-consumer's FailedDeliveryCheckService reads it. */
    @Test
    void failureCounts_areFailedAttemptsPerHost_inTheWindow() {
        Instant now = Instant.now();
        attempt("a", now.minusSeconds(60), 2, "x", "https://afnemer.example.nl/ebms/msh");
        attempt("b", now.minusSeconds(30), 2, "x", "https://afnemer.example.nl:8443/ebms");
        attempt("c", now.minusSeconds(30), 1, null, "https://afnemer.example.nl/ebms/msh");
        attempt("d", now.minusSeconds(3600), 2, "x", "https://afnemer.example.nl/ebms/msh");

        Map<String, Integer> counts = STRICT.readValue(
                http.get().uri("/ebms/throttle/failurecounts?minutes=5").retrieve().body(String.class),
                new TypeReference<Map<String, Integer>>() { });

        assertThat(counts).containsOnly(entry("afnemer.example.nl", 2));
    }

    @Test
    void failureCounts_defaultsToAnHour_andRefusesANonsenseWindow() {
        attempt("a", Instant.now().minusSeconds(1800), 2, "x", "https://afnemer.example.nl/ebms/msh");

        assertThat(http.get().uri("/ebms/throttle/failurecounts").retrieve().body(String.class))
                .isEqualTo("{\"afnemer.example.nl\":1}");
        assertThat(status(http.get().uri("/ebms/throttle/failurecounts?minutes=0")))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- authentication (P4-A3) ----------

    @Test
    void everyAbsorbedPath_requiresCredentials() {
        RestClient anonymous = client(null, null);

        assertThat(status(anonymous.post().uri("/ebms/message").contentType(MediaType.APPLICATION_JSON).body("{}")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(anonymous.post().uri("/ebms/message/count").contentType(MediaType.APPLICATION_JSON).body("{}")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(anonymous.get().uri("/ebms/message/deliverylog?messageId=x")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(anonymous.get().uri("/ebms/throttle/failurecounts")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // The write: without auth this moved anyone's failed messages anywhere.
        assertThat(status(anonymous.put().uri("/ebms/message/move/from/{a}/to/{b}", "cpa-old", NEW_CPA)))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE cpa_id = 'cpa-old'", Integer.class))
                .isEqualTo(4);
    }

    @Test
    void bothServiceAccounts_areAccepted_andAWrongPasswordIsNot() {
        assertThat(status(client(USER, PASSWORD).get().uri("/ebms/throttle/failurecounts")))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(client(API_SERVICE_USER, API_SERVICE_PASSWORD).get().uri("/ebms/throttle/failurecounts")))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(client(API_SERVICE_USER, "wrong").get().uri("/ebms/throttle/failurecounts")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Protecting /ebms/message must not catch /ebms/msh, where partners post without a password. */
    @Test
    void thePartnerEndpointBesideThem_staysOpen() {
        HttpStatus status = status(client(null, null).post().uri("/ebms/msh")
                .contentType(MediaType.TEXT_XML).body("<not-a-soap-envelope/>"));

        assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- helpers ----------

    private PaginatedResponse<EbmsMessage> search(EbmsMessageFilterDto filter, String query) {
        String body = http.post().uri("/ebms/message" + query).contentType(MediaType.APPLICATION_JSON)
                .body(filter).retrieve().body(String.class);
        JsonNode tree = STRICT.readTree(body);
        assertFields(tree, PaginatedResponse.class);
        tree.get("content").forEach(node -> assertFields(node, EbmsMessage.class));
        return STRICT.readValue(body, new TypeReference<PaginatedResponse<EbmsMessage>>() { });
    }

    private <T> List<T> readStrict(String body, TypeReference<List<T>> type, Class<?> element) {
        STRICT.readTree(body).forEach(node -> assertFields(node, element));
        return STRICT.readValue(body, type);
    }

    /** Exactly the caller's fields: none missing (a silent null there), none extra. */
    private static void assertFields(JsonNode node, Class<?> recordType) {
        Set<String> expected = Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> actual = new TreeSet<>(node.propertyNames());
        assertThat(actual).as("fields of %s", recordType.getSimpleName()).isEqualTo(expected);
    }

    private static List<String> ids(PaginatedResponse<EbmsMessage> page) {
        return page.content().stream().map(EbmsMessage::messageId).toList();
    }

    private static EbmsMessage only(PaginatedResponse<EbmsMessage> page) {
        assertThat(page.content()).hasSize(1);
        return page.content().getFirst();
    }

    private static HttpStatus status(RestClient.RequestHeadersSpec<?> request) {
        return (HttpStatus) request.retrieve().toBodilessEntity().getStatusCode();
    }

    private RestClient client(String user, String password) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (request, response) -> { });
        if (user != null) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(user, password));
        }
        return builder.build();
    }

    private static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    private void insert(String messageId, String conversationId, String cpaId, String direction,
                        String status, boolean processed, boolean ackRequested,
                        String fromPartyId, String fromPartyType, String toPartyId, String toPartyType,
                        String refToMessageId, Instant timestamp) {
        List<Object> args = new ArrayList<>(List.of(UUID.randomUUID(), messageId, conversationId, cpaId));
        args.addAll(Arrays.asList(fromPartyId, fromPartyType, toPartyId, toPartyType));
        args.addAll(Arrays.asList(direction, status, Timestamp.from(timestamp), Timestamp.from(timestamp),
                ackRequested, refToMessageId, processed, Timestamp.from(timestamp)));
        jdbc.update("""
                INSERT INTO messages (id, message_id, conversation_id, cpa_id,
                    from_party_id, from_party_type, to_party_id, to_party_type,
                    service, action, direction, status, "timestamp", created_at,
                    ack_requested, ref_to_message_id, processed, status_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'urn:osb:services:test', 'Test', ?, ?, ?, ?, ?, ?, ?, ?)
                """, args.toArray());
    }

    private void attempt(String messageId, Instant at, int status, String error) {
        attempt(messageId, at, status, error, "https://partner-a.example.nl/ebms/msh");
    }

    private void attempt(String messageId, Instant at, int status, String error, String uri) {
        jdbc.update("INSERT INTO delivery_log (message_id, time_stamp, uri, status, error_message) VALUES (?, ?, ?, ?, ?)",
                messageId, Timestamp.from(at), uri, (short) status, error);
    }
}
