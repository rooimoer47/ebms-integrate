package dev.ebms.adapter.in.api.v19;

import dev.ebms.application.port.in.SendMessageUseCase.SendRequest;
import dev.ebms.domain.Payload;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code MessageRequest} of the ebms-core v19 REST contract, as jms-consumer and
 * cpa-service send it.
 *
 * <p>Only {@code cpaId}, {@code fromPartyId}, {@code fromRole}, {@code service} and
 * {@code action} are required by the schema, but real callers send more: jms-consumer sets the
 * message id itself and names both parties, because with an intermediary the message is
 * addressed to the intermediary rather than to the afnemer named in the CPA.
 */
public record V19MessageRequest(
        MessageRequestProperties properties,
        List<DataSource> dataSources
) {

    public SendRequest toSendRequest() {
        MessageRequestProperties props = properties != null
                ? properties
                : new MessageRequestProperties(null, null, null, null, null, null, null, null, null, null);

        return new SendRequest(
                props.cpaId(),
                props.conversationId(),
                props.service(),
                props.action(),
                toPayloads(),
                props.messageId(),
                props.fromPartyId(),
                props.fromRole(),
                props.toPartyId(),
                props.toRole(),
                props.refToMessageId());
    }

    private List<Payload> toPayloads() {
        if (dataSources == null) {
            return List.of();
        }
        List<Payload> payloads = new ArrayList<>(dataSources.size());
        for (int i = 0; i < dataSources.size(); i++) {
            payloads.add(dataSources.get(i).toPayload(i + 1));
        }
        return payloads;
    }

    public record MessageRequestProperties(
            String cpaId,
            String fromPartyId,
            String fromRole,
            String toPartyId,
            String toRole,
            String service,
            String action,
            String conversationId,
            String messageId,
            String refToMessageId
    ) {
    }

    public record DataSource(String name, String contentId, String contentType, byte[] content) {

        /**
         * jms-consumer sends only {@code content} and {@code contentType}, so a content id has
         * to be invented: the ebMS manifest references each payload as {@code cid:<contentId>},
         * and a null would be serialised literally into the outgoing message. Content ids only
         * need to be unique within one message's MIME package, so the position will do.
         */
        Payload toPayload(int position) {
            return new Payload(resolveContentId(position), contentType, content);
        }

        private String resolveContentId(int position) {
            if (contentId != null && !contentId.isBlank()) {
                return contentId;
            }
            if (name != null && !name.isBlank()) {
                return name;
            }
            return "payload-" + position;
        }
    }
}
