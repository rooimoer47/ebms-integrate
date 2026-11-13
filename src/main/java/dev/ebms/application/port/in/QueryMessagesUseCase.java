package dev.ebms.application.port.in;

import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.EbmsMessage.Direction;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Payload;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueryMessagesUseCase {

    Optional<EbmsMessage> findById(UUID id);

    List<EbmsMessage> findAll(Direction direction, MessageStatus status);

    Optional<Payload> findPayload(UUID messageId, String contentId);

    /**
     * Fetch a message by its ebMS message id, optionally marking it processed in the same
     * transaction.
     *
     * <p>The two happen together because the client does them in one call: jms-producer reads a
     * message with {@code process=true} and takes responsibility for it from that moment. A
     * fetch that committed without the flag would hand the same message out twice.
     */
    Optional<EbmsMessage> fetchByMessageId(String messageId, boolean markProcessed);
}
