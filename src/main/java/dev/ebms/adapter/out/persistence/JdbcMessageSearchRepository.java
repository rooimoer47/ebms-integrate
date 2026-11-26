package dev.ebms.adapter.out.persistence;

import dev.ebms.application.port.out.MessageSearchRepository;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.EbmsMessage.Direction;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Party;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The console's message search, ported from ebms-api-service's {@code EbmsMessageSqlFilter} onto
 * our {@code messages} table. Plain SQL rather than JPA: the filter is a set of optional
 * conditions with a Postgres-specific twist or two, and every column read here is payload-free
 * by construction, which an entity graph would only make easier to get wrong.
 *
 * <p>Two things differ from ebms-core's table, and both are handled here rather than leaked to
 * the caller:
 * <ul>
 *   <li><b>Party ids.</b> ebms-core stored {@code urn:osb:oin:<oin>}. We store what the message
 *       carried: the caller's prefixed id for an outbound message, or the bare {@code eb:PartyId}
 *       text with its {@code eb:type} in a separate column for an inbound one. A party filter
 *       therefore matches the id as stored <em>or</em> type and id joined the way ebms-core
 *       joined them.</li>
 *   <li><b>Status.</b> ebms-core stored the contract's number. We store our own state, so the
 *       caller hands over the column combinations a number maps from.</li>
 * </ul>
 */
@Component
public class JdbcMessageSearchRepository implements MessageSearchRepository {

    /** ebms-core prefixed a numeric party id before matching; so do we. */
    static final String OIN_PREFIX = "urn:osb:oin:";

    /**
     * An intermediary's message id embeds the afnemer's 20-digit OIN ({@code <id>@<oin>}), so a
     * search on a bare OIN matches it anywhere in the id, as ebms-api-service did.
     */
    private static final Pattern OIN = Pattern.compile("[0-9]{20}");
    private static final Pattern NUMERIC = Pattern.compile("[0-9]+");

    private static final String COLUMNS = """
            id, message_id, conversation_id, cpa_id,
            from_party_id, from_party_type, from_role, to_party_id, to_party_type, to_role,
            service, action, direction, status, "timestamp", retry_count, next_retry_at,
            ack_requested, ref_to_message_id, processed, status_time
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMessageSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Summary> search(Criteria criteria, long offset, int limit) {
        Where where = where(criteria);
        where.params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM messages" + where.sql
                        // ebms-api-service declared newest-first but never put it in its SQL; the
                        // id makes the order total, so paging never repeats or skips a row.
                        + " ORDER BY \"timestamp\" DESC, id LIMIT :limit OFFSET :offset",
                where.params, SUMMARY);
    }

    @Override
    @Transactional(readOnly = true)
    public long count(Criteria criteria) {
        Where where = where(criteria);
        Long count = jdbc.queryForObject("SELECT count(*) FROM messages" + where.sql, where.params, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public int moveFailed(String fromCpaId, String toCpaId) {
        return jdbc.update("UPDATE messages SET cpa_id = :to WHERE cpa_id = :from AND status = :failed",
                new MapSqlParameterSource()
                        .addValue("from", fromCpaId)
                        .addValue("to", toCpaId)
                        .addValue("failed", MessageStatus.FAILED.name()));
    }

    // ---- the filter ----

    private record Where(String sql, MapSqlParameterSource params) {
    }

    private static Where where(Criteria c) {
        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (hasText(c.cpaId())) {
            conditions.add("cpa_id = :cpaId");
            params.addValue("cpaId", c.cpaId());
        }
        if (hasText(c.conversationId())) {
            conditions.add("conversation_id = :conversationId");
            params.addValue("conversationId", c.conversationId());
        }
        if (hasText(c.messageId())) {
            conditions.add(messageIdCondition("message_id", "messageId", c.messageId(), params));
        }
        if (hasText(c.refToMessageId())) {
            conditions.add(messageIdCondition("ref_to_message_id", "refToMessageId", c.refToMessageId(), params));
        }
        if (hasText(c.fromPartyId())) {
            conditions.add(partyCondition("from", ":fromPartyId"));
            params.addValue("fromPartyId", qualifyNumeric(c.fromPartyId()));
        }
        if (hasText(c.toPartyId())) {
            conditions.add(partyCondition("to", ":toPartyId"));
            params.addValue("toPartyId", qualifyNumeric(c.toPartyId()));
        }
        if (c.statuses() != null) {
            conditions.add(statusCondition(c.statuses(), params));
        }
        if (c.from() != null) {
            conditions.add("\"timestamp\" >= :dateFrom");
            params.addValue("dateFrom", Timestamp.from(c.from()));
        }
        if (c.until() != null) {
            conditions.add("\"timestamp\" < :dateUntil");
            params.addValue("dateUntil", Timestamp.from(c.until()));
        }
        if (hasText(c.globalSearchTerm())) {
            String global = globalCondition(c);
            if (global != null) {
                conditions.add(global);
                params.addValue("globalSearchTerm", c.globalSearchTerm());
            }
        }

        String sql = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        return new Where(sql, params);
    }

    private static String messageIdCondition(String column, String param, String value,
                                             MapSqlParameterSource params) {
        params.addValue(param, value);
        if (OIN.matcher(value).matches()) {
            params.addValue(param + "Like", "%" + value + "%");
            return "(" + column + " = :" + param + " OR " + column + " LIKE :" + param + "Like)";
        }
        return column + " = :" + param;
    }

    private static String partyCondition(String side, String param) {
        return "(" + side + "_party_id = " + param
                + " OR " + side + "_party_type || ':' || " + side + "_party_id = " + param + ")";
    }

    private static String statusCondition(java.util.Set<StatusMatch> statuses, MapSqlParameterSource params) {
        if (statuses.isEmpty()) {
            return "FALSE";
        }
        List<String> alternatives = new ArrayList<>();
        int i = 0;
        for (StatusMatch match : statuses) {
            alternatives.add("(status = :status" + i + " AND processed = :processed" + i
                    + " AND ack_requested = :ack" + i + ")");
            params.addValue("status" + i, match.status().name())
                    .addValue("processed" + i, match.processed())
                    .addValue("ack" + i, match.ackRequested());
            i++;
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * The term is matched exactly against each id field the filter does not already constrain,
     * any one sufficing -- ebms-api-service's semantics. With every field filtered there is
     * nothing left to search and the term is ignored, as it was there.
     */
    private static String globalCondition(Criteria c) {
        List<String> alternatives = new ArrayList<>();
        if (!hasText(c.cpaId())) alternatives.add("cpa_id = :globalSearchTerm");
        if (!hasText(c.conversationId())) alternatives.add("conversation_id = :globalSearchTerm");
        if (!hasText(c.messageId())) alternatives.add("message_id = :globalSearchTerm");
        if (!hasText(c.refToMessageId())) alternatives.add("ref_to_message_id = :globalSearchTerm");
        if (!hasText(c.fromPartyId())) alternatives.add(partyCondition("from", ":globalSearchTerm"));
        if (!hasText(c.toPartyId())) alternatives.add(partyCondition("to", ":globalSearchTerm"));
        return alternatives.isEmpty() ? null : "(" + String.join(" OR ", alternatives) + ")";
    }

    private static String qualifyNumeric(String partyId) {
        return NUMERIC.matcher(partyId).matches() ? OIN_PREFIX + partyId : partyId;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // ---- the row ----

    private static final RowMapper<Summary> SUMMARY = (rs, row) -> {
        Timestamp nextRetry = rs.getTimestamp("next_retry_at");
        EbmsMessage message = new EbmsMessage(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("message_id"),
                rs.getString("conversation_id"),
                rs.getString("cpa_id"),
                new Party(rs.getString("from_party_id"), rs.getString("from_party_type"), rs.getString("from_role")),
                new Party(rs.getString("to_party_id"), rs.getString("to_party_type"), rs.getString("to_role")),
                rs.getString("service"),
                rs.getString("action"),
                rs.getTimestamp("timestamp").toInstant(),
                List.of(),
                Direction.valueOf(rs.getString("direction")),
                MessageStatus.valueOf(rs.getString("status")),
                rs.getInt("retry_count"),
                nextRetry == null ? null : nextRetry.toInstant(),
                rs.getBoolean("ack_requested"),
                rs.getString("ref_to_message_id"),
                rs.getBoolean("processed"));
        return new Summary(message, rs.getTimestamp("status_time").toInstant());
    };
}
