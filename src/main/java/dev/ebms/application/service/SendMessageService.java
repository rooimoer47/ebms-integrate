package dev.ebms.application.service;

import dev.ebms.application.port.in.SendMessageUseCase;
import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.InboundMessageParser;
import dev.ebms.application.port.out.MessageEventPublisher;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.application.port.out.MessageTransport;
import dev.ebms.application.port.out.OutboundMessageSerializer;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Party;
import dev.ebms.domain.exception.CpaNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final InboundMessageParser inboundParser;
    private final MeterRegistry meterRegistry;
    private final MessageEventPublisher eventPublisher;

    @Value("${ebms.msh-id}")
    private String mshId;

    public SendMessageService(MessageRepository messageRepository, CpaRepository cpaRepository,
                              MessageTransport transport, OutboundMessageSerializer serializer,
                              InboundMessageParser inboundParser, MeterRegistry meterRegistry,
                              MessageEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.cpaRepository = cpaRepository;
        this.transport = transport;
        this.serializer = serializer;
        this.inboundParser = inboundParser;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Longest message id we will store. ebMS message ids are RFC 2822 Message-IDs; the ids our
     * callers generate are well under this, so a longer one means a caller bug, and failing
     * fast beats persisting something a partner MSH will reject.
     */
    private static final int MAX_MESSAGE_ID_LENGTH = 255;

    @Override
    @Transactional
    public UUID send(SendRequest request) {
        Cpa cpa = cpaRepository.findByCpaId(request.cpaId())
                .orElseThrow(() -> new CpaNotFoundException(request.cpaId()));

        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();

        String messageId = resolveMessageId(request.messageId());
        Party from = resolveParty(cpa.fromParty(), request.fromPartyId(), request.fromRole());
        Party to = resolveParty(cpa.toParty(), request.toPartyId(), request.toRole());

        EbmsMessage message = EbmsMessage.newOutbound(messageId, conversationId, cpa.cpaId(),
                from, to, request.service(), request.action(),
                request.payloads(), cpa.ackRequested(), request.refToMessageId());

        EbmsMessage saved = messageRepository.save(message);

        attemptSend(saved, cpa);

        return saved.id();
    }

    /**
     * The caller's message id wins when it supplies one. Correlation across the platform is
     * keyed on it -- jms-consumer passes through the id it received on the queue -- so
     * generating our own over the top would break tracking end to end.
     */
    private String resolveMessageId(String requested) {
        if (requested == null || requested.isBlank()) {
            return UUID.randomUUID() + "@" + mshId;
        }
        String trimmed = requested.trim();
        if (trimmed.length() > MAX_MESSAGE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "messageId exceeds " + MAX_MESSAGE_ID_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * A party named by the caller overrides the CPA's, which is what makes intermediary
     * routing expressible: the CPA names the afnemer, while the caller may need the message
     * addressed to the intermediary that fronts it. Each half falls back independently, so a
     * caller can override the role alone.
     */
    private Party resolveParty(Party fromCpa, String partyId, String role) {
        String resolvedId = partyId != null && !partyId.isBlank() ? partyId.trim() : fromCpa.partyId();
        String resolvedRole = role != null && !role.isBlank() ? role.trim() : fromCpa.role();
        // The party id type stays the CPA's even when the id is overridden. Callers send a bare
        // party id with no type -- ebms-core's MessageRequestProperties has no field for one --
        // and an overriding id is the same kind of identifier as the one it replaces: in this
        // platform both are urn:osb:oin. Dropping the type here would silently omit eb:type.
        return new Party(resolvedId, fromCpa.partyIdType(), resolvedRole);
    }

    void attemptSend(EbmsMessage message, Cpa cpa) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            OutboundMessageSerializer.SerializedMessage serialized = serializer.serialize(message, cpa.recipientCert());
            MessageTransport.TransportResult result = transport.send(cpa.transportUrl(),
                    serialized.body(), serialized.contentType());

            if (result.success()) {
                log.info("Message {} sent to {}", message.messageId(), cpa.transportUrl());
                MessageStatus finalStatus = isSynchronousAck(message, result)
                        ? MessageStatus.ACKED
                        : MessageStatus.SENT;
                if (finalStatus == MessageStatus.ACKED) {
                    log.info("Message {} acknowledged synchronously", message.messageId());
                }
                messageRepository.update(message.withStatus(finalStatus));
                recordOutbound(cpa.cpaId(), finalStatus.name().toLowerCase());
                eventPublisher.publish(MessageEventPublisher.EventType.DELIVERED, message);
            } else {
                log.warn("Failed to send message {}, scheduling retry", message.messageId());
                int newCount = message.retryCount() + 1;
                if (newCount >= cpa.retries()) {
                    messageRepository.update(message.withStatus(MessageStatus.FAILED));
                    recordOutbound(cpa.cpaId(), "failed");
                    eventPublisher.publish(MessageEventPublisher.EventType.FAILED, message);
                } else {
                    Instant nextRetry = Instant.now().plus(cpa.retryInterval());
                    messageRepository.update(message.withRetry(newCount, nextRetry));
                    recordOutbound(cpa.cpaId(), "retrying");
                }
            }
        } finally {
            sample.stop(Timer.builder("ebms.send.duration")
                    .tag("cpa_id", cpa.cpaId())
                    .register(meterRegistry));
        }
    }

    private void recordOutbound(String cpaId, String result) {
        meterRegistry.counter("ebms.messages.outbound", "cpa_id", cpaId, "result", result)
                .increment();
    }

    private boolean isSynchronousAck(EbmsMessage sent, MessageTransport.TransportResult result) {
        byte[] body = result.responseBody();
        if (body == null || body.length == 0) return false;
        String ct = result.responseContentType() != null
                ? result.responseContentType().toString()
                : "text/xml";
        return inboundParser.tryParse(body, ct)
                .filter(r -> "Acknowledgment".equals(r.action()))
                .filter(r -> sent.messageId().equals(r.refToMessageId()))
                .isPresent();
    }
}
