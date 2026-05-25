package dev.ebms.application.service;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Party;
import dev.ebms.domain.exception.CpaNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ReceiveMessageServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock CpaRepository cpaRepository;
    ReceiveMessageService service;

    @BeforeEach
    void setupCpa() {
        service = new ReceiveMessageService(messageRepository, cpaRepository, new SimpleMeterRegistry());
        lenient().when(cpaRepository.findByCpaId("cpa-001")).thenReturn(Optional.of(cpa(true)));
    }

    @Test
    void receive_newMessageWithAckRequested_savesAndReturnsAck() {
        EbmsMessage message = inbound("msg-001@test", true);
        when(messageRepository.findByMessageId("msg-001@test")).thenReturn(Optional.empty());
        when(messageRepository.save(any())).thenReturn(message);

        Optional<EbmsMessage> result = service.receive(message);

        verify(messageRepository).save(message);
        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo("Acknowledgment");
        assertThat(result.get().refToMessageId()).isEqualTo("msg-001@test");
    }

    @Test
    void receive_newMessageWithoutAckRequested_savesAndReturnsEmpty() {
        EbmsMessage message = inbound("msg-002@test", false);
        when(messageRepository.findByMessageId("msg-002@test")).thenReturn(Optional.empty());
        when(messageRepository.save(any())).thenReturn(message);

        Optional<EbmsMessage> result = service.receive(message);

        verify(messageRepository).save(message);
        assertThat(result).isEmpty();
    }

    @Test
    void receive_duplicateWithAckRequested_doesNotSaveAndReturnsAck() {
        EbmsMessage original = inbound("msg-003@test", true);
        when(messageRepository.findByMessageId("msg-003@test")).thenReturn(Optional.of(original));

        Optional<EbmsMessage> result = service.receive(original);

        verify(messageRepository, never()).save(any());
        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo("Acknowledgment");
        assertThat(result.get().refToMessageId()).isEqualTo("msg-003@test");
    }

    @Test
    void receive_duplicateWithoutAckRequested_doesNotSaveAndReturnsEmpty() {
        EbmsMessage original = inbound("msg-004@test", false);
        when(messageRepository.findByMessageId("msg-004@test")).thenReturn(Optional.of(original));

        Optional<EbmsMessage> result = service.receive(original);

        verify(messageRepository, never()).save(any());
        assertThat(result).isEmpty();
    }

    @Test
    void receive_acknowledgment_marksOriginalMessageAsAcked() {
        EbmsMessage original = outboundSent("msg-010@test");
        EbmsMessage ack = inboundAck("ack-010@partner.com", "msg-010@test");
        when(messageRepository.findByMessageId("msg-010@test")).thenReturn(Optional.of(original));
        when(messageRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<EbmsMessage> result = service.receive(ack);

        assertThat(result).isEmpty();
        verify(messageRepository).update(argThat(m -> m.status() == MessageStatus.ACKED));
        verify(messageRepository, never()).save(any());
    }

    @Test
    void receive_duplicateAcknowledgment_isIdempotent() {
        EbmsMessage alreadyAcked = outboundSent("msg-011@test").withStatus(MessageStatus.ACKED);
        EbmsMessage ack = inboundAck("ack-011@partner.com", "msg-011@test");
        when(messageRepository.findByMessageId("msg-011@test")).thenReturn(Optional.of(alreadyAcked));

        Optional<EbmsMessage> result = service.receive(ack);

        assertThat(result).isEmpty();
        verify(messageRepository, never()).update(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void receive_unknownCpa_throwsCpaNotFoundException() {
        EbmsMessage message = inbound("msg-020@test", false);
        when(cpaRepository.findByCpaId("cpa-001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(message))
                .isInstanceOf(CpaNotFoundException.class);

        verify(messageRepository, never()).save(any());
    }

    @Test
    void receive_acknowledgmentForUnknownMessage_returnsEmpty() {
        EbmsMessage ack = inboundAck("ack-012@partner.com", "unknown@test");
        when(messageRepository.findByMessageId("unknown@test")).thenReturn(Optional.empty());

        Optional<EbmsMessage> result = service.receive(ack);

        assertThat(result).isEmpty();
        verify(messageRepository, never()).update(any());
        verify(messageRepository, never()).save(any());
    }

    private static EbmsMessage inbound(String messageId, boolean ackRequested) {
        return EbmsMessage.newInbound(
                messageId, "conv-001", "cpa-001",
                Party.of("partner-a"), Party.of("our-company"),
                "OrderService", "NewOrder",
                Instant.parse("2025-05-18T12:00:00Z"),
                List.of(), ackRequested, null);
    }

    private static EbmsMessage inboundAck(String ackMessageId, String refToMessageId) {
        return EbmsMessage.newInbound(
                ackMessageId, "conv-001", "cpa-001",
                Party.of("partner-a"), Party.of("our-company"),
                "urn:oasis:names:tc:ebxml-msg:service", "Acknowledgment",
                Instant.now(), List.of(), false, refToMessageId);
    }

    private static Cpa cpa(boolean duplicateElimination) {
        return new Cpa("cpa-001", Party.of("our-company"), Party.of("partner-a"),
                "http://partner-a.example.com/ebms/msh", true, duplicateElimination,
                3, Duration.ofSeconds(60), null);
    }

    private static EbmsMessage outboundSent(String messageId) {
        return EbmsMessage.newOutbound(
                messageId, "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder",
                List.of(), true).withStatus(MessageStatus.SENT);
    }
}
