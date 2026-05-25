package dev.ebms.adapter.out.persistence;

import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.EbmsMessage.Direction;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Party;
import dev.ebms.domain.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JpaMessageRepositoryAdapter implements MessageRepository {

    private final MessageJpaRepository jpa;

    public JpaMessageRepositoryAdapter(MessageJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public EbmsMessage save(EbmsMessage message) {
        MessageEntity entity = toEntity(message);
        entity.setCreatedAt(Instant.now());
        for (Payload payload : message.payloads()) {
            PayloadEntity pe = new PayloadEntity(UUID.randomUUID(), entity,
                    payload.contentId(), payload.mimeType(), payload.content());
            entity.getPayloads().add(pe);
        }
        return toDomain(jpa.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EbmsMessage> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EbmsMessage> findByMessageId(String messageId) {
        return jpa.findByMessageId(messageId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EbmsMessage> findByDirectionAndStatus(Direction direction, MessageStatus status) {
        return jpa.findByDirectionAndStatus(direction, status).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EbmsMessage> findPendingRetries(Instant now) {
        return jpa.findPendingRetries(now).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EbmsMessage> findUnprocessedInbound() {
        return jpa.findUnprocessedInbound().stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public EbmsMessage update(EbmsMessage message) {
        MessageEntity entity = jpa.findById(message.id()).orElseThrow();
        entity.setStatus(message.status());
        entity.setRetryCount(message.retryCount());
        entity.setNextRetryAt(message.nextRetryAt());
        return toDomain(jpa.save(entity));
    }

    @Override
    @Transactional
    public void markProcessed(UUID id) {
        jpa.findById(id).ifPresent(entity -> {
            entity.setProcessed(true);
            jpa.save(entity);
        });
    }

    private MessageEntity toEntity(EbmsMessage m) {
        MessageEntity e = new MessageEntity();
        e.setId(m.id());
        e.setMessageId(m.messageId());
        e.setConversationId(m.conversationId());
        e.setCpaId(m.cpaId());
        e.setFromPartyId(m.from().partyId());
        e.setFromPartyType(m.from().partyIdType());
        e.setToPartyId(m.to().partyId());
        e.setToPartyType(m.to().partyIdType());
        e.setService(m.service());
        e.setAction(m.action());
        e.setDirection(m.direction());
        e.setStatus(m.status());
        e.setTimestamp(m.timestamp());
        e.setRetryCount(m.retryCount());
        e.setNextRetryAt(m.nextRetryAt());
        e.setAckRequested(m.ackRequested());
        e.setRefToMessageId(m.refToMessageId());
        e.setProcessed(m.processed());
        return e;
    }

    private EbmsMessage toDomain(MessageEntity e) {
        List<Payload> payloads = e.getPayloads().stream()
                .map(p -> new Payload(p.getContentId(), p.getMimeType(), p.getContent()))
                .toList();
        return new EbmsMessage(
                e.getId(), e.getMessageId(), e.getConversationId(), e.getCpaId(),
                new Party(e.getFromPartyId(), e.getFromPartyType()),
                new Party(e.getToPartyId(), e.getToPartyType()),
                e.getService(), e.getAction(), e.getTimestamp(), payloads,
                e.getDirection(), e.getStatus(), e.getRetryCount(), e.getNextRetryAt(),
                e.isAckRequested(), e.getRefToMessageId(), e.isProcessed()
        );
    }
}
