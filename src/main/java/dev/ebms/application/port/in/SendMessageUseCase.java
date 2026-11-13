package dev.ebms.application.port.in;

import dev.ebms.domain.Payload;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SendMessageUseCase {

    SendResult send(SendRequest request);

    /**
     * @param id        our internal row id
     * @param messageId the ebMS message id, which the caller may have supplied or we may have
     *                  generated -- either way it is what the v19 contract answers with
     */
    record SendResult(UUID id, String messageId) {}

    /**
     * Send the content of an existing message again as a new ebMS message, as the contract's
     * {@code PUT /messages/{messageId}} defines it: the original is left alone and a freshly
     * identified message goes out carrying the same payloads and parties.
     *
     * @return empty when no message has that id, which the contract reports as a 404
     */
    Optional<SendResult> resend(String messageId);

    /**
     * A request to send a message. Everything after {@code payloads} is optional and comes
     * from the caller rather than the CPA.
     *
     * <p>Callers in this platform are not passive: jms-consumer generates its own
     * {@code messageId} -- sometimes as {@code <id>@<afnemerOin>} -- and names both parties
     * and roles explicitly, with {@code toPartyId} being the intermediary rather than the
     * afnemer when one is involved. Those values must survive, so each falls back to the
     * CPA's only when the caller leaves it null.
     */
    record SendRequest(
            String cpaId,
            String conversationId,
            String service,
            String action,
            List<Payload> payloads,
            String messageId,
            String fromPartyId,
            String fromRole,
            String toPartyId,
            String toRole,
            String refToMessageId
    ) {
        /** Everything derived from the CPA, and a generated message id. */
        public SendRequest(String cpaId, String conversationId, String service, String action,
                           List<Payload> payloads) {
            this(cpaId, conversationId, service, action, payloads,
                    null, null, null, null, null, null);
        }
    }
}
