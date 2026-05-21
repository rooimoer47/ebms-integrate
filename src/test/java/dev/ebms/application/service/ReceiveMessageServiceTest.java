package dev.ebms.application.service;

import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Party;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReceiveMessageServiceTest {

    @Mock MessageRepository messageRepository;
    @InjectMocks ReceiveMessageService service;

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

    private static EbmsMessage inbound(String messageId, boolean ackRequested) {
        return EbmsMessage.newInbound(
                messageId, "conv-001", "cpa-001",
                Party.of("partner-a"), Party.of("our-company"),
                "OrderService", "NewOrder",
                Instant.parse("2025-05-18T12:00:00Z"),
                List.of(), ackRequested);
    }
}
