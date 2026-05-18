package dev.ebms.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EbmsMessage(
        UUID id,
        String messageId,
        String conversationId,
        String cpaId,
        Party from,
        Party to,
        String service,
        String action,
        Instant timestamp,
        List<Payload> payloads,
        Direction direction,
        MessageStatus status,
        int retryCount,
        Instant nextRetryAt,
        boolean ackRequested,
        String refToMessageId
) {
    public enum Direction { INBOUND, OUTBOUND }

    public EbmsMessage withStatus(MessageStatus newStatus) {
        return new EbmsMessage(id, messageId, conversationId, cpaId, from, to, service, action,
                timestamp, payloads, direction, newStatus, retryCount, nextRetryAt, ackRequested, refToMessageId);
    }

    public EbmsMessage withRetry(int newRetryCount, Instant newNextRetryAt) {
        return new EbmsMessage(id, messageId, conversationId, cpaId, from, to, service, action,
                timestamp, payloads, direction, status, newRetryCount, newNextRetryAt, ackRequested, refToMessageId);
    }

    public static EbmsMessage newInbound(String messageId, String conversationId, String cpaId,
                                         Party from, Party to, String service, String action,
                                         Instant timestamp, List<Payload> payloads,
                                         boolean ackRequested) {
        return new EbmsMessage(UUID.randomUUID(), messageId, conversationId, cpaId, from, to,
                service, action, timestamp, payloads, Direction.INBOUND, MessageStatus.RECEIVED,
                0, null, ackRequested, null);
    }

    public static EbmsMessage newOutbound(String messageId, String conversationId, String cpaId,
                                          Party from, Party to, String service, String action,
                                          List<Payload> payloads, boolean ackRequested) {
        return new EbmsMessage(UUID.randomUUID(), messageId, conversationId, cpaId, from, to,
                service, action, Instant.now(), payloads, Direction.OUTBOUND, MessageStatus.PENDING_SEND,
                0, null, ackRequested, null);
    }

    public static EbmsMessage newAck(String messageId, String conversationId, String cpaId,
                                     Party from, Party to, String refToMessageId) {
        return new EbmsMessage(UUID.randomUUID(), messageId, conversationId, cpaId, from, to,
                "urn:oasis:names:tc:ebxml-msg:service", "Acknowledgment",
                Instant.now(), List.of(), Direction.OUTBOUND, MessageStatus.SENT,
                0, null, false, refToMessageId);
    }
}
