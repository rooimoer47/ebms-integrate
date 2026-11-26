package dev.ebms.adapter.out.persistence;

import dev.ebms.application.port.out.DeliveryLog;
import dev.ebms.domain.DeliveryAttempt;
import dev.ebms.domain.DeliveryAttempt.Outcome;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code delivery_log} table, in plain SQL: an append-only history with two aggregate reads
 * gains nothing from an entity, and the host extraction below is a Postgres expression anyway.
 */
@Component
public class JdbcDeliveryLog implements DeliveryLog {

    /**
     * The host of a URI, as ebms-api-service computed it: scheme and anything up to the first
     * {@code /} or {@code :} after it. Kept identical so the keys jms-consumer throttles on do
     * not change at cutover.
     */
    static final String HOST_OF_URI = "substring(uri from '.*://([^/:]*)')";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDeliveryLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(DeliveryAttempt attempt) {
        jdbc.update("""
                INSERT INTO delivery_log (message_id, time_stamp, uri, status, error_message)
                VALUES (:messageId, :timeStamp, :uri, :status, :errorMessage)
                """,
                new MapSqlParameterSource()
                        .addValue("messageId", attempt.messageId())
                        .addValue("timeStamp", Timestamp.from(attempt.attemptedAt()))
                        .addValue("uri", attempt.uri())
                        .addValue("status", attempt.outcome().code())
                        .addValue("errorMessage", attempt.errorMessage()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryAttempt> findByMessageId(String messageId) {
        return jdbc.query("""
                SELECT message_id, time_stamp, uri, status, error_message
                FROM delivery_log
                WHERE message_id = :messageId
                ORDER BY time_stamp, id
                """,
                Map.of("messageId", messageId),
                (rs, row) -> new DeliveryAttempt(
                        rs.getString("message_id"),
                        rs.getTimestamp("time_stamp").toInstant(),
                        rs.getString("uri"),
                        Outcome.ofCode(rs.getShort("status")),
                        rs.getString("error_message")));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> failureCountsByHostSince(Instant since) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT " + HOST_OF_URI + " AS host, count(*) AS failures"
                        + " FROM delivery_log"
                        + " WHERE status = :failed AND time_stamp >= :since"
                        + " GROUP BY host",
                new MapSqlParameterSource()
                        .addValue("failed", Outcome.FAILED.code())
                        .addValue("since", Timestamp.from(since)),
                rs -> {
                    String host = rs.getString("host");
                    // A URI with no scheme has no host; ebms-api-service dropped those too.
                    if (host != null) {
                        counts.put(host, rs.getLong("failures"));
                    }
                });
        return counts;
    }

    @Override
    @Transactional
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM delivery_log WHERE time_stamp < :cutoff",
                Map.of("cutoff", Timestamp.from(cutoff)));
    }
}
