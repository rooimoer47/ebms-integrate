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

    /** Messages that are PENDING_SEND and ready for their next attempt. */
    List<EbmsMessage> findPendingRetries(Instant now);

    EbmsMessage update(EbmsMessage message);
}
