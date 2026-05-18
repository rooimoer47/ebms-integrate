package dev.ebms.application.service;

import dev.ebms.application.port.in.SendMessageUseCase;
import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.application.port.out.MessageTransport;
import dev.ebms.application.port.out.OutboundMessageSerializer;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.exception.CpaNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class SendMessageService implements SendMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendMessageService.class);

    private final MessageRepository messageRepository;
    private final CpaRepository cpaRepository;
    private final MessageTransport transport;
    private final OutboundMessageSerializer serializer;

    @Value("${ebms.msh-id}")
    private String mshId;

    public SendMessageService(MessageRepository messageRepository, CpaRepository cpaRepository,
                              MessageTransport transport, OutboundMessageSerializer serializer) {
        this.messageRepository = messageRepository;
        this.cpaRepository = cpaRepository;
        this.transport = transport;
        this.serializer = serializer;
    }

    @Override
    @Transactional
    public UUID send(SendRequest request) {
        Cpa cpa = cpaRepository.findByCpaId(request.cpaId())
                .orElseThrow(() -> new CpaNotFoundException(request.cpaId()));

        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();

        String messageId = UUID.randomUUID() + "@" + mshId;

        EbmsMessage message = EbmsMessage.newOutbound(messageId, conversationId, cpa.cpaId(),
                cpa.fromParty(), cpa.toParty(), request.service(), request.action(),
                request.payloads(), cpa.ackRequested());

        EbmsMessage saved = messageRepository.save(message);

        attemptSend(saved, cpa);

        return saved.id();
    }

    void attemptSend(EbmsMessage message, Cpa cpa) {
        OutboundMessageSerializer.SerializedMessage serialized = serializer.serialize(message);
        MessageTransport.TransportResult result = transport.send(cpa.transportUrl(),
                serialized.body(), serialized.contentType());

        if (result.success()) {
            log.info("Message {} sent to {}", message.messageId(), cpa.transportUrl());
            messageRepository.update(message.withStatus(MessageStatus.SENT));
        } else {
            log.warn("Failed to send message {}, scheduling retry", message.messageId());
            int newCount = message.retryCount() + 1;
            if (newCount >= cpa.retries()) {
                messageRepository.update(message.withStatus(MessageStatus.FAILED));
            } else {
                Instant nextRetry = Instant.now().plus(cpa.retryInterval());
                messageRepository.update(message.withRetry(newCount, nextRetry));
            }
        }
    }
}
