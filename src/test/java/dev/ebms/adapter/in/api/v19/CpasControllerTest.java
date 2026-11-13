package dev.ebms.adapter.in.api.v19;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The six operations cpa-service calls, exercised through the real HTTP stack with the request
 * shapes its own client produces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CpasControllerTest {

    static final String USER = "ebms";
    static final String PASSWORD = "test-secret";
    static final String BASE = "/service/rest/v19/cpas";

    static final EmbeddedPostgres POSTGRES;
    static final Path CPA_DIR;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            CPA_DIR = Files.createTempDirectory("ebms-cpa-api");
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
        registry.add("ebms.host-party-ids", () -> "00000004003214345001");
        registry.add("ebms.security.api.username", () -> USER);
        registry.add("ebms.security.api.password", () -> PASSWORD);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    RestClient http;
    String cpa;
    String cpaId;

    @BeforeEach
    void setUp() throws Exception {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(USER, PASSWORD))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        jdbc.update("DELETE FROM cpas");
        http.delete().uri(BASE + "/cache").retrieve().toBodilessEntity();

        cpa = Files.readString(Path.of(getClass().getResource("/real-cpas/valid_cpa.xml").toURI()))
                .replaceAll("<ns1:End>[^<]*</ns1:End>", "<ns1:End>2099-01-01T00:00:00Z</ns1:End>");
        cpaId = "DGL-ONTVANGEN-1-0$1-0_22222222222222222222-00000004003214345001"
                + "_D8B5DA3079F311E8B020005056810F3E";
    }

    /**
     * The URI cpa-service actually puts on the wire: Sanitizer.urlEncode turns {@code $} into
     * {@code %24}, then RestClient percent-encodes that {@code %} again. Built as a URI rather
     * than a template so the test client does not encode it a third time.
     */
    private URI doubleEncodedUri() {
        return URI.create("http://localhost:" + port + BASE + "/"
                + cpaId.replace("$", "%2524"));
    }

    /** Exactly what EbmsCoreCpaClient.insertCPA sends: a text/plain body, overwrite in the query. */
    private ResponseEntity<String> insert(String document, boolean overwrite) {
        return insert(document.getBytes(StandardCharsets.UTF_8), overwrite);
    }

    private ResponseEntity<String> insert(byte[] document, boolean overwrite) {
        return http.post()
                .uri(BASE + "?overwrite=" + overwrite)
                .contentType(MediaType.TEXT_PLAIN)
                .body(document)
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    void insert_storesTheCpaAndAnswersWithItsId() {
        ResponseEntity<String> response = insert(cpa, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(cpaId);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN))
                .isTrue();
    }

    /** A console sync posts every active CPA on every run, so this is the common case. */
    @Test
    void insert_sameDocumentTwiceWithOverwrite_succeedsBothTimes() {
        assertThat(insert(cpa, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(insert(cpa, true).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void insert_withoutOverwrite_conflictsAndLeavesTheStoredDocumentAlone() {
        insert(cpa, true);

        ResponseEntity<String> second = insert(cpa, false);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("already exists");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cpas", Integer.class)).isEqualTo(1);
    }

    @Test
    void insert_invalidCpa_is400WithTheReasonInTheBody() {
        ResponseEntity<String> response = insert("<not-a-cpa/>", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("cpa-service shows this text to an operator")
                .contains("CPPA 2.0 schema");
    }

    @Test
    void insert_expiredCpa_isRejected() throws Exception {
        String expired = Files.readString(Path.of(getClass().getResource("/real-cpas/valid_cpa.xml").toURI()));

        ResponseEntity<String> response = insert(expired, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("expired on");
    }

    @Test
    void validate_doesNotStoreAnything() {
        ResponseEntity<Void> response = http.post()
                .uri(BASE + "/validate")
                .contentType(MediaType.TEXT_PLAIN)
                .body(cpa)
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cpas", Integer.class)).isZero();
    }

    @Test
    void validate_invalidCpa_is400() {
        ResponseEntity<String> response = http.post()
                .uri(BASE + "/validate")
                .contentType(MediaType.TEXT_PLAIN)
                .body("<not-a-cpa/>")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** cpa-service deserialises this into List<String>, not into objects. */
    @Test
    void list_isAJsonArrayOfBareIds() {
        insert(cpa, true);

        ResponseEntity<List> response =
                http.get().uri(BASE).retrieve().toEntity(List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(cpaId);
    }

    @Test
    void get_returnsTheStoredDocumentItself() {
        insert(cpa, true);

        ResponseEntity<String> response =
                http.get().uri(BASE + "/" + cpaId).retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(cpa);
        assertThat(response.getHeaders().getContentType().getCharset())
                .as("a text/* response with no charset is ISO-8859-1 to a strict client")
                .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void get_unknownCpaId_is404() {
        assertThat(http.get().uri(BASE + "/never-stored").retrieve()
                .toBodilessEntity().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The id cpa-service actually sends: it URL-encodes the {@code $} itself, and RestClient
     * encodes the resulting {@code %} again, so the literal text {@code %24} arrives here.
     */
    @Test
    void get_doubleEncodedCpaId_resolvesTheStoredCpa() {
        insert(cpa, true);

        ResponseEntity<String> response = http.get()
                .uri(doubleEncodedUri())
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(cpa);
    }

    @Test
    void delete_removesTheCpa() {
        insert(cpa, true);

        ResponseEntity<Void> response = http.delete()
                .uri(doubleEncodedUri())
                // cpa-service's client declares application/json on the DELETE, with no body.
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cpas", Integer.class)).isZero();
    }

    /**
     * A 404 here would abort cpa-service's own delete: its handler rethrows unconditionally and
     * its caller catches only CPAServiceException, so the row it believes gone would stay.
     */
    @Test
    void delete_unknownCpaId_is204AndNotA404() {
        assertThat(http.delete().uri(BASE + "/never-stored")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteCache_rebuildsTheProjectionWithoutTouchingTheTable() {
        insert(cpa, true);
        jdbc.update("UPDATE cpas SET uploaded_by = 'out-of-band'");

        ResponseEntity<Void> response =
                http.delete().uri(BASE + "/cache").retrieve().toBodilessEntity();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cpas", Integer.class))
                .as("the path says cache, not store")
                .isEqualTo(1);
        assertThat(http.get().uri(BASE).retrieve().body(List.class)).containsExactly(cpaId);
    }
}
