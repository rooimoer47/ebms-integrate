package dev.ebms.application.service;

import dev.ebms.application.port.in.ReceiveMessageUseCase;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.MessageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ReceiveMessageService implements ReceiveMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReceiveMessageService.class);

    private final MessageRepository messageRepository;

    public ReceiveMessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional
    public Optional<EbmsMessage> receive(EbmsMessage message) {
        if ("Acknowledgment".equals(message.action())) {
            return processAcknowledgment(message);
        }

        Optional<EbmsMessage> existing = messageRepository.findByMessageId(message.messageId());
        if (existing.isPresent()) {
            return existing.get().ackRequested()
                    ? Optional.of(buildAck(existing.get()))
                    : Optional.empty();
        }

        messageRepository.save(message);

        return message.ackRequested()
                ? Optional.of(buildAck(message))
                : Optional.empty();
    }

    private Optional<EbmsMessage> processAcknowledgment(EbmsMessage ack) {
        String refId = ack.refToMessageId();
        if (refId == null) {
            log.warn("Received Acknowledgment {} with no RefToMessageId — ignoring", ack.messageId());
            return Optional.empty();
        }
        messageRepository.findByMessageId(refId).ifPresentOrElse(
                original -> {
                    if (original.status() == MessageStatus.SENT) {
                        messageRepository.update(original.withStatus(MessageStatus.ACKED));
                        log.info("Message {} acknowledged", refId);
                    } else {
                        log.debug("Received duplicate or late ack for message {} (status: {})", refId, original.status());
                    }
                },
                () -> log.warn("Received Acknowledgment for unknown message {}", refId)
        );
        return Optional.empty();
    }

    private EbmsMessage buildAck(EbmsMessage original) {
        String ackMessageId = UUID.randomUUID() + "@" + "ebms.dev";
        return EbmsMessage.newAck(ackMessageId, original.conversationId(), original.cpaId(),
                original.to(), original.from(), original.messageId());
    }
}
