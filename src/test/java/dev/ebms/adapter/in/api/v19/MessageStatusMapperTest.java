package dev.ebms.adapter.in.api.v19;

import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Party;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageStatusMapperTest {

    @Test
    void inboundAwaitingFetch_isReceived() {
        assertThat(MessageStatusMapper.toV19(inbound(false)))
                .isEqualTo(V19MessageStatus.RECEIVED);
    }

    @Test
    void inboundAlreadyFetched_isProcessed() {
        assertThat(MessageStatusMapper.toV19(inbound(true)))
                .isEqualTo(V19MessageStatus.PROCESSED);
    }

    @Test
    void awaitingFirstAttemptOrRetry_isCreated() {
        assertThat(MessageStatusMapper.toV19(outbound(MessageStatus.PENDING_SEND, true)))
                .isEqualTo(V19MessageStatus.CREATED);
    }

    @Test
    void sentWithAckRequested_isCreated_becauseTheAckIsTheProof() {
        assertThat(MessageStatusMapper.toV19(outbound(MessageStatus.SENT, true)))
                .isEqualTo(V19MessageStatus.CREATED);
    }

    @Test
    void sentWithoutAckRequested_isDelivered_becauseNoAckIsComing() {
        assertThat(MessageStatusMapper.toV19(outbound(MessageStatus.SENT, false)))
                .isEqualTo(V19MessageStatus.DELIVERED);
    }

    @Test
    void acknowledged_isDelivered() {
        assertThat(MessageStatusMapper.toV19(outbound(MessageStatus.ACKED, true)))
                .isEqualTo(V19MessageStatus.DELIVERED);
    }

    @Test
    void retriesExhausted_isDeliveryFailed_notFailed() {
        // The contract's FAILED means an inbound processing failure, which is a different
        // thing from giving up on delivery.
        assertThat(MessageStatusMapper.toV19(outbound(MessageStatus.FAILED, true)))
                .isEqualTo(V19MessageStatus.DELIVERY_FAILED);
    }

    @ParameterizedTest
    @EnumSource(MessageStatus.class)
    void everyInternalStatusMapsToSomething(MessageStatus status) {
        assertThat(MessageStatusMapper.toV19(outbound(status, true))).isNotNull();
        assertThat(MessageStatusMapper.toV19(outbound(status, false))).isNotNull();
    }

    private EbmsMessage inbound(boolean processed) {
        EbmsMessage message = EbmsMessage.newInbound(
                "in-001@partner.example.nl", "conv-001", "cpa-001",
                new Party("urn:osb:oin:00000001810220350000", "urn:osb:oin", "Afnemer"),
                new Party("urn:osb:oin:00000001822477348001", "urn:osb:oin", "Bronhouder"),
                "urn:osb:services:verstrekking", "verstrekkingDoorLV",
                Instant.now(), List.of(), false, null);
        return processed ? message.withProcessed(true) : message;
    }

    private EbmsMessage outbound(MessageStatus status, boolean ackRequested) {
        return EbmsMessage.newOutbound(
                "out-001@msh.test", "conv-001", "cpa-001",
                new Party("urn:osb:oin:00000001822477348001", "urn:osb:oin", "Bronhouder"),
                new Party("urn:osb:oin:00000001810220350000", "urn:osb:oin", "Afnemer"),
                "urn:osb:services:verstrekking", "verstrekkingDoorLV",
                List.of(), ackRequested).withStatus(status);
    }
}
