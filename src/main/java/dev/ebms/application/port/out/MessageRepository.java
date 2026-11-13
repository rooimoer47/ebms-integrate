package dev.ebms.application.port.out;

import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.EbmsMessage.Direction;
import dev.ebms.domain.MessageStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository {

    EbmsMessage save(EbmsMessage message);

    Optional<EbmsMessage> findById(UUID id);

    Optional<EbmsMessage> findByMessageId(String messageId);

    List<EbmsMessage> findByDirectionAndStatus(Direction direction, MessageStatus status);

    /**
     * Ids of the messages that are PENDING_SEND and ready for their next attempt.
     *
     * <p>Ids rather than messages, deliberately. The retry loop sends one at a time, so loading
     * the whole batch would hold every pending payload -- up to {@code maxMessageSize} each --
     * in the heap at once. The caller fetches each message, with its payloads, as it reaches it.
     */
    List<UUID> findPendingRetryIds(Instant now);

    EbmsMessage update(EbmsMessage message);

    void markProcessed(UUID id);
}
