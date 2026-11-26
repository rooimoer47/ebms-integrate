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

    /** Numbered as ebms-core's {@code DeliveryTaskStatus}, which the console reads. */
    public enum Outcome {
        SUCCEEDED(1),
        FAILED(2),
        EXPIRED(3);

        private final short code;

        Outcome(int code) {
            this.code = (short) code;
        }

        public short code() {
            return code;
        }

        public static Outcome ofCode(short code) {
            for (Outcome outcome : values()) {
                if (outcome.code == code) {
                    return outcome;
                }
            }
            throw new IllegalArgumentException("Unknown delivery outcome code " + code);
        }
    }
}
