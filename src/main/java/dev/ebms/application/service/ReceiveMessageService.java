package dev.ebms.application.service;

import dev.ebms.application.port.in.ReceiveMessageUseCase;
import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.exception.CpaNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ReceiveMessageService implements ReceiveMessageUseCase {

    private final MessageRepository messageRepository;
    private final CpaRepository cpaRepository;

    public ReceiveMessageService(MessageRepository messageRepository, CpaRepository cpaRepository) {
        this.messageRepository = messageRepository;
        this.cpaRepository = cpaRepository;
    }

    @Override
    @Transactional
    public Optional<EbmsMessage> receive(EbmsMessage message) {
        Optional<EbmsMessage> existing = messageRepository.findByMessageId(message.messageId());
        if (existing.isPresent()) {
            return existing.get().ackRequested()
                    ? Optional.of(buildAck(existing.get()))
                    : Optional.empty();
        }

        Cpa cpa = cpaRepository.findByCpaId(message.cpaId())
                .orElseThrow(() -> new CpaNotFoundException(message.cpaId()));

        messageRepository.save(message);

        return message.ackRequested()
                ? Optional.of(buildAck(message))
                : Optional.empty();
    }

    private EbmsMessage buildAck(EbmsMessage original) {
        String ackMessageId = UUID.randomUUID() + "@" + "ebms.dev";
        return EbmsMessage.newAck(ackMessageId, original.conversationId(), original.cpaId(),
                original.to(), original.from(), original.messageId());
    }
}
