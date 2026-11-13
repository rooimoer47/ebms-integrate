package dev.ebms.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Basic auth protects, and -- just as load-bearing -- what it must not protect.
 *
 * <p>Asserted through the real HTTP stack rather than with {@code @WebMvcTest}: Spring Boot 4
 * split the test slices into per-module artifacts this project does not depend on, and a
 * standalone setup would not exercise the actual filter chain, which is the whole subject here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSecurityTest {

    static final String USER = "ebms";
    static final String PASSWORD = "test-secret";

    static final EmbeddedPostgres POSTGRES;
    static final Path CPA_DIR;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            CPA_DIR = Files.createTempDirectory("ebms-cpa-auth");
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
    }

    @LocalServerPort
    int port;

    RestClient anonymous;
    RestClient authenticated;

    @BeforeEach
    void setUp() {
        anonymous = client(null, null);
        authenticated = client(USER, PASSWORD);
    }

    private RestClient client(String user, String password) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { });
        if (user != null) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(user, password));
        }
        return builder.build();
    }

    private HttpStatus get(RestClient client, String uri) {
        return (HttpStatus) client.get().uri(uri).retrieve().toBodilessEntity().getStatusCode();
    }

    @Test
    void serviceApi_withoutCredentials_isUnauthorized() {
        assertThat(get(anonymous, "/service/rest/v19/ebms/messages/unknown"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(anonymous, "/api/messages")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void serviceApi_withWrongPassword_isUnauthorized() {
        assertThat(get(client(USER, "wrong"), "/api/messages")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(client("someone-else", PASSWORD), "/api/messages"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void serviceApi_withCredentials_reachesTheApplication() {
        assertThat(get(authenticated, "/api/messages")).isEqualTo(HttpStatus.OK);
        // 404 rather than 401: the request reached the controller and the id is simply unknown.
        assertThat(get(authenticated, "/service/rest/v19/ebms/messages/unknown"))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Partners hold a client certificate, not a password. A 401 here would reject every inbound
     * message from every partner, which is the one failure this phase must not introduce.
     */
    @Test
    void partnerMsh_staysOpen() {
        ResponseEntity<String> response = anonymous.post()
                .uri("/ebms/msh")
                .contentType(MediaType.TEXT_XML)
                .body("<not-a-soap-envelope/>")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** A 401 on the probe path is an unready pod, then a CrashLoopBackOff. */
    @Test
    void healthProbe_staysOpen() {
        assertThat(get(anonymous, "/actuator/health")).isEqualTo(HttpStatus.OK);
    }

    @Test
    void metricsAndInfo_areProtected() {
        assertThat(get(anonymous, "/actuator/prometheus")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(anonymous, "/actuator/info")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(authenticated, "/actuator/prometheus")).isEqualTo(HttpStatus.OK);
    }
}
