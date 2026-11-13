package dev.ebms.adapter.in.api.v19;

import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Payload;

import java.time.Instant;
import java.util.List;

/**
 * The {@code Message} of the ebms-core v19 REST contract.
 *
 * <p>Field names and nesting are fixed by the contract, not chosen here: the clients
 * deserialise this into generated {@code nl.clockwork.ebms.model.*} types. See
 * {@code jms-producer/src/main/resources/openapi/ebms.yml}.
 *
 * <p>Note that the contract's {@code Party} carries {@code partyId} and {@code role} only --
 * there is no field for the party id type, so {@link dev.ebms.domain.Party#partyIdType()} does
 * not survive a round trip through this representation.
 */
public record V19Message(
        MessageProperties properties,
        List<DataSource> dataSources
) {

    public static V19Message from(EbmsMessage message) {
        return new V19Message(
                MessageProperties.from(message),
                message.payloads().stream().map(DataSource::from).toList());
    }

    public record MessageProperties(
            String cpaId,
            Party fromParty,
            Party toParty,
            String service,
            String action,
            Instant timestamp,
            String conversationId,
            String messageId,
            String refToMessageId,
            V19MessageStatus messageStatus
    ) {
        static MessageProperties from(EbmsMessage message) {
            return new MessageProperties(
                    message.cpaId(),
                    Party.from(message.from()),
                    Party.from(message.to()),
                    message.service(),
                    message.action(),
                    message.timestamp(),
                    message.conversationId(),
                    message.messageId(),
                    message.refToMessageId(),
                    MessageStatusMapper.toV19(message));
        }
    }

    public record Party(String partyId, String role) {
        static Party from(dev.ebms.domain.Party party) {
            return new Party(party.partyId(), party.role());
        }
    }

    /**
     * A payload as the contract models it. {@code content} is a {@code byte[]} so that Jackson
     * writes it base64-encoded, which is what {@code format: byte} means in the schema.
     *
     * <p>{@code name} is the attachment's filename and {@code contentId} its MIME Content-ID.
     * We hold only the latter, so both are reported from it rather than inventing a filename.
     */
    public record DataSource(String name, String contentId, String contentType, byte[] content) {
        static DataSource from(Payload payload) {
            return new DataSource(payload.contentId(), payload.contentId(),
                    payload.mimeType(), payload.content());
        }
    }
}
