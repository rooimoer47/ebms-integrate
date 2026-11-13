package dev.ebms.application.service;

import dev.ebms.application.port.in.QueryMessagesUseCase;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.EbmsMessage.Direction;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class QueryMessagesService implements QueryMessagesUseCase {

    private final MessageRepository messageRepository;

    public QueryMessagesService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EbmsMessage> findById(UUID id) {
        return messageRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EbmsMessage> findAll(Direction direction, MessageStatus status) {
        return messageRepository.findByDirectionAndStatus(direction, status);
    }

    @Override
    @Transactional
    public Optional<EbmsMessage> fetchByMessageId(String messageId, boolean markProcessed) {
        Optional<EbmsMessage> message = messageRepository.findByMessageId(messageId);
        if (markProcessed) {
            return message.map(m -> {
                if (!m.processed()) {
                    messageRepository.markProcessed(m.id());
                }
                // Return the post-call state: the contract defines process=true as giving the
                // message the PROCESSED status, so the response has to reflect that.
                return m.withProcessed(true);
            });
        }
        return message;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payload> findPayload(UUID messageId, String contentId) {
        return messageRepository.findById(messageId)
                .flatMap(msg -> msg.payloads().stream()
                        .filter(p -> p.contentId().equals(contentId))
                        .findFirst());
    }
}
