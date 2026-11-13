package dev.ebms.adapter.in.api.v19;

import dev.ebms.domain.EbmsMessage;

/**
 * Maps our six internal statuses onto the ten of the v19 contract.
 *
 * <p>The two sets are not a subset of one another: ours distinguishes "transmitted" from
 * "acknowledged", which the contract has no value for, while the contract has values for
 * states we do not model. The interesting case is therefore {@code SENT} — the HTTP POST
 * succeeded but no acknowledgement has arrived — and it depends on whether one was ever
 * coming:
 *
 * <ul>
 *   <li>when the CPA requested an acknowledgement, the ack is the proof of delivery, so an
 *       unacknowledged message is still only {@code CREATED}. Reporting it as
 *       {@code DELIVERED} would hide exactly the partner-side failures operators need to
 *       see;</li>
 *   <li>when it did not, no ack will ever arrive, and the HTTP 200 is all the confirmation
 *       the protocol will produce — so {@code DELIVERED} is the honest answer. Reporting
 *       {@code CREATED} would leave such messages looking permanently undelivered.</li>
 * </ul>
 *
 * <p>The switch is deliberately exhaustive with no {@code default}: a new internal status
 * must break the build here rather than fall through to something plausible.
 */
public final class MessageStatusMapper {

    private MessageStatusMapper() {
    }

    public static V19MessageStatus toV19(EbmsMessage message) {
        return switch (message.status()) {
            // Inbound. The contract treats "fetched by the client" as a status; we hold it as
            // a flag, so the flag wins while the status stays RECEIVED.
            case RECEIVED -> message.processed()
                    ? V19MessageStatus.PROCESSED
                    : V19MessageStatus.RECEIVED;

            // Outbound, not yet away: queued for a first attempt or awaiting a retry.
            case PENDING_SEND -> V19MessageStatus.CREATED;

            case SENT -> message.ackRequested()
                    ? V19MessageStatus.CREATED
                    : V19MessageStatus.DELIVERED;

            // ACKED is the acknowledged case. DELIVERED is currently unreachable -- nothing
            // assigns it -- but is handled so the switch stays exhaustive.
            case ACKED, DELIVERED -> V19MessageStatus.DELIVERED;

            // Retries exhausted. DELIVERY_FAILED, not FAILED: the contract's FAILED is an
            // inbound processing failure, which is a different thing.
            case FAILED -> V19MessageStatus.DELIVERY_FAILED;
        };
    }
}
