package dev.ebms.application.service;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.InboundMessageParser;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.application.port.out.MessageTransport;
import dev.ebms.application.port.out.MessageTransport.TransportResult;
import dev.ebms.application.port.out.OutboundMessageSerializer;
import dev.ebms.application.port.out.OutboundMessageSerializer.SerializedMessage;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Party;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SendMessageServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock CpaRepository cpaRepository;
    @Mock MessageTransport transport;
    @Mock OutboundMessageSerializer serializer;
    @Mock InboundMessageParser inboundParser;

    SendMessageService service;

    @BeforeEach
    void setup() {
        service = new SendMessageService(messageRepository, cpaRepository,
                transport, serializer, inboundParser, new SimpleMeterRegistry(), (event, msg) -> {});
    }

    @Test
    void attemptSend_success_noResponseBody_marksAsSent() {
        EbmsMessage msg = outbound("msg-001@test");
        when(serializer.serialize(any(), any())).thenReturn(new SerializedMessage(new byte[]{1}, "text/xml"));
        when(transport.send(any(), any(), any()))
                .thenReturn(new TransportResult(true, new byte[0], null));

        service.attemptSend(msg, cpa());

        ArgumentCaptor<EbmsMessage> captor = ArgumentCaptor.forClass(EbmsMessage.class);
        verify(messageRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void attemptSend_responseContainsMatchingAck_marksAsAcked() {
        EbmsMessage msg = outbound("msg-002@test");
        EbmsMessage ack = ack("msg-002@test");
        when(serializer.serialize(any(), any())).thenReturn(new SerializedMessage(new byte[]{1}, "text/xml"));
        when(transport.send(any(), any(), any()))
                .thenReturn(new TransportResult(true, new byte[]{60}, MediaType.TEXT_XML));
        when(inboundParser.tryParse(any(), any())).thenReturn(Optional.of(ack));

        service.attemptSend(msg, cpa());

        ArgumentCaptor<EbmsMessage> captor = ArgumentCaptor.forClass(EbmsMessage.class);
        verify(messageRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(MessageStatus.ACKED);
    }

    @Test
    void attemptSend_responseContainsAckForDifferentMessage_marksAsSent() {
        EbmsMessage msg = outbound("msg-003@test");
        EbmsMessage ack = ack("other-msg@test");
        when(serializer.serialize(any(), any())).thenReturn(new SerializedMessage(new byte[]{1}, "text/xml"));
        when(transport.send(any(), any(), any()))
                .thenReturn(new TransportResult(true, new byte[]{60}, MediaType.TEXT_XML));
        when(inboundParser.tryParse(any(), any())).thenReturn(Optional.of(ack));

        service.attemptSend(msg, cpa());

        ArgumentCaptor<EbmsMessage> captor = ArgumentCaptor.forClass(EbmsMessage.class);
        verify(messageRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void attemptSend_failure_schedulesRetry() {
        EbmsMessage msg = outbound("msg-004@test");
        when(serializer.serialize(any(), any())).thenReturn(new SerializedMessage(new byte[]{1}, "text/xml"));
        when(transport.send(any(), any(), any()))
                .thenReturn(new TransportResult(false, null, null));

        service.attemptSend(msg, cpa());

        ArgumentCaptor<EbmsMessage> captor = ArgumentCaptor.forClass(EbmsMessage.class);
        verify(messageRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isNotEqualTo(MessageStatus.SENT);
        assertThat(captor.getValue().status()).isNotEqualTo(MessageStatus.ACKED);
    }

    private EbmsMessage outbound(String messageId) {
        return EbmsMessage.newOutbound(messageId, "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder", List.of(), true);
    }

    private EbmsMessage ack(String refToMessageId) {
        return EbmsMessage.newAck(UUID.randomUUID() + "@ebms.dev",
                "conv-001", "cpa-001",
                Party.of("partner-a"), Party.of("our-company"),
                refToMessageId);
    }

    private Cpa cpa() {
        return new Cpa("cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "http://partner-a.example.com/ebms/msh",
                true, true, 3, Duration.ofSeconds(60), null);
    }
}
