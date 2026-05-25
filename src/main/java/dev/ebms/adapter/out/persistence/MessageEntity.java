package dev.ebms.adapter.out.persistence;

import dev.ebms.domain.EbmsMessage.Direction;
import dev.ebms.domain.MessageStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    private UUID id;

    @Column(name = "message_id", unique = true, nullable = false)
    private String messageId;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "cpa_id", nullable = false)
    private String cpaId;

    @Column(name = "from_party_id", nullable = false)
    private String fromPartyId;

    @Column(name = "from_party_type")
    private String fromPartyType;

    @Column(name = "to_party_id", nullable = false)
    private String toPartyId;

    @Column(name = "to_party_type")
    private String toPartyType;

    @Column(nullable = false)
    private String service;

    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageStatus status;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "ack_requested", nullable = false)
    private boolean ackRequested;

    @Column(name = "ref_to_message_id")
    private String refToMessageId;

    @Column(nullable = false)
    private boolean processed;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<PayloadEntity> payloads = new ArrayList<>();

    protected MessageEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getCpaId() { return cpaId; }
    public void setCpaId(String cpaId) { this.cpaId = cpaId; }
    public String getFromPartyId() { return fromPartyId; }
    public void setFromPartyId(String fromPartyId) { this.fromPartyId = fromPartyId; }
    public String getFromPartyType() { return fromPartyType; }
    public void setFromPartyType(String fromPartyType) { this.fromPartyType = fromPartyType; }
    public String getToPartyId() { return toPartyId; }
    public void setToPartyId(String toPartyId) { this.toPartyId = toPartyId; }
    public String getToPartyType() { return toPartyType; }
    public void setToPartyType(String toPartyType) { this.toPartyType = toPartyType; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public boolean isAckRequested() { return ackRequested; }
    public void setAckRequested(boolean ackRequested) { this.ackRequested = ackRequested; }
    public String getRefToMessageId() { return refToMessageId; }
    public void setRefToMessageId(String refToMessageId) { this.refToMessageId = refToMessageId; }
    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }
    public List<PayloadEntity> getPayloads() { return payloads; }
}
