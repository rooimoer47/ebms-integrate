package dev.ebms.application.port.out;

import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.MessageStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Searching and re-pointing stored messages, for the admin console paths absorbed from
 * ebms-api-service. Separate from {@link MessageRepository} because nothing on the send or
 * receive path needs it, and because every method here must stay payload-free: a result page
 * that touched payloads would load up to {@code maxMessageSize} per row.
 */
public interface MessageSearchRepository {

    /** One combination of the stored columns that a contract status code maps from. */
    record StatusMatch(MessageStatus status, boolean processed, boolean ackRequested) {
    }

    /**
     * The console's filter. Blank strings mean "no filter". {@code statuses} null means any
     * status; an empty set means none, which is what a code we never produce must find.
     * {@code until} is exclusive.
     */
    record Criteria(
            String globalSearchTerm,
            String cpaId,
            String conversationId,
            String messageId,
            String refToMessageId,
            String fromPartyId,
            String toPartyId,
            Set<StatusMatch> statuses,
            Instant from,
            Instant until) {

        public static Criteria none() {
            return new Criteria(null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** A message as the console lists it: no payloads, plus when it last changed status. */
    record Summary(EbmsMessage message, Instant statusTime) {
    }

    /** Newest first. */
    List<Summary> search(Criteria criteria, long offset, int limit);

    long count(Criteria criteria);

    /**
     * Moves the messages whose delivery failed from one CPA to another, so a resend goes out
     * under the new agreement. Delivered and pending messages stay where they are, as they do in
     * ebms-core. Returns how many moved.
     */
    int moveFailed(String fromCpaId, String toCpaId);
}
