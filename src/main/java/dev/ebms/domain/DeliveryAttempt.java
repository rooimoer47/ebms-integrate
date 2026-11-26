package dev.ebms.domain;

import java.time.Instant;

/**
 * One attempt to deliver an outbound message to a partner, successful or not.
 *
 * <p>A message records only its retry count; this is the history behind it. It feeds the
 * console's delivery log and the failure counts jms-consumer throttles on.
 */
public record DeliveryAttempt(
        String messageId,
        Instant attemptedAt,
        String uri,
        Outcome outcome,
        String errorMessage) {

    public enum Outcome { SUCCEEDED, FAILED, EXPIRED }
}
