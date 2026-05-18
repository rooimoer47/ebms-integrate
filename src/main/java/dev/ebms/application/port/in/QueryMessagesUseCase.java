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
}
