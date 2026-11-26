package dev.ebms.adapter.out.persistence;

import dev.ebms.application.port.out.DeliveryLog;
import dev.ebms.domain.DeliveryAttempt;
import dev.ebms.domain.DeliveryAttempt.Outcome;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The delivery log against real Postgres: the host extraction is a Postgres expression. */
@SpringBootTest
class JdbcDeliveryLogTest {

    static final EmbeddedPostgres POSTGRES;
    static final Path CPA_DIR;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            CPA_DIR = Files.createTempDirectory("ebms-delivery-log");
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
        registry.add("ebms.security.api.username", () -> "ebms");
        registry.add("ebms.security.api.password", () -> "test-secret");
    }

    @Autowired
    DeliveryLog deliveryLog;

    @Autowired
    JdbcTemplate jdbc;

    final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM delivery_log");
    }

    @Test
    void anAttemptReadsBackAsWritten_oldestFirst() {
        deliveryLog.record(new DeliveryAttempt("m-1", now, "https://partner-a.nl/ebms", Outcome.SUCCEEDED, null));
        deliveryLog.record(new DeliveryAttempt("m-1", now.minusSeconds(60), "https://partner-a.nl/ebms",
                Outcome.FAILED, "503 Service Unavailable"));
        deliveryLog.record(new DeliveryAttempt("m-2", now, "https://partner-b.nl/ebms", Outcome.SUCCEEDED, null));

        assertThat(deliveryLog.findByMessageId("m-1")).containsExactly(
                new DeliveryAttempt("m-1", now.minusSeconds(60), "https://partner-a.nl/ebms",
                        Outcome.FAILED, "503 Service Unavailable"),
                new DeliveryAttempt("m-1", now, "https://partner-a.nl/ebms", Outcome.SUCCEEDED, null));
    }

    @Test
    void anUnknownMessage_hasAnEmptyHistory() {
        assertThat(deliveryLog.findByMessageId("never-sent")).isEmpty();
    }

    /** Text, like messages.status: ebms-core's 1/2/3 is applied only at the API edge. */
    @Test
    void outcomes_areStoredAsTheirNames() {
        deliveryLog.record(new DeliveryAttempt("m-1", now, "https://a.nl", Outcome.FAILED, "x"));

        assertThat(jdbc.queryForObject("SELECT status FROM delivery_log", String.class)).isEqualTo("FAILED");
    }

    @Test
    void anUnknownOutcome_isRefusedByTheTable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO delivery_log (message_id, time_stamp, status) VALUES ('m', now(), 'MAYBE')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * Keyed by host, as ebms-api-service keyed it and as jms-consumer reduces its own
     * throttleKey: port, path and scheme do not split a partner's count.
     */
    @Test
    void failureCounts_areGroupedByHost_andCountOnlyFailuresInTheWindow() {
        failed("https://afnemer.example.nl/ebms/msh", now.minusSeconds(30));
        failed("https://afnemer.example.nl:8443/other/path", now.minusSeconds(20));
        failed("http://afnemer.example.nl/ebms", now.minusSeconds(10));
        failed("https://other.example.nl/ebms", now.minusSeconds(10));
        failed("https://afnemer.example.nl/ebms/msh", now.minus(Duration.ofMinutes(10)));
        deliveryLog.record(new DeliveryAttempt("ok", now, "https://afnemer.example.nl/ebms/msh",
                Outcome.SUCCEEDED, null));

        assertThat(deliveryLog.failureCountsByHostSince(now.minus(Duration.ofMinutes(5))))
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("afnemer.example.nl", 3L),
                        org.assertj.core.api.Assertions.entry("other.example.nl", 1L));
    }

    @Test
    void failureCounts_dropAUriWithNoHost() {
        failed("not-a-url", now);

        assertThat(deliveryLog.failureCountsByHostSince(now.minusSeconds(60))).isEmpty();
    }

    @Test
    void purge_removesOnlyRowsOlderThanTheCutoff() {
        failed("https://a.nl", now.minus(Duration.ofDays(40)));
        failed("https://a.nl", now.minus(Duration.ofDays(1)));

        assertThat(deliveryLog.purgeOlderThan(now.minus(Duration.ofDays(30)))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_log", Integer.class)).isEqualTo(1);
    }

    private void failed(String uri, Instant at) {
        deliveryLog.record(new DeliveryAttempt("m-" + at.toEpochMilli() + uri.hashCode(), at, uri,
                Outcome.FAILED, "refused"));
    }
}
