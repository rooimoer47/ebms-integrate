package dev.ebms.application.port.in;

import dev.ebms.domain.Payload;

import java.util.List;
import java.util.UUID;

public interface SendMessageUseCase {

    UUID send(SendRequest request);

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
