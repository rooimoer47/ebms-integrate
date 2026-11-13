package dev.ebms.application.service;

import dev.ebms.application.port.in.SendMessageUseCase;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        ReflectionTestUtils.setField(service, "mshId", "msh.test");
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

    // ---------- P1-2: the caller owns the message identity ----------

    @Test
    void send_withCallerSuppliedMessageId_usesItVerbatim() {
        arrangeSuccessfulSend();

        service.send(new SendMessageUseCase.SendRequest(
                "cpa-001", "conv-001", "OrderService", "NewOrder", List.of(),
                "AFN-12345@00000001810220350000", null, null, null, null, null));

        assertThat(savedMessage().messageId()).isEqualTo("AFN-12345@00000001810220350000");
    }

    @Test
    void send_withoutMessageId_generatesOneFromTheMshId() {
        arrangeSuccessfulSend();

        service.send(new SendMessageUseCase.SendRequest(
                "cpa-001", "conv-001", "OrderService", "NewOrder", List.of()));

        assertThat(savedMessage().messageId()).endsWith("@msh.test");
    }

    @Test
    void send_blankMessageId_fallsBackToAGeneratedOne() {
        arrangeSuccessfulSend();

        service.send(new SendMessageUseCase.SendRequest(
                "cpa-001", "conv-001", "OrderService", "NewOrder", List.of(),
                "   ", null, null, null, null, null));

        assertThat(savedMessage().messageId()).endsWith("@msh.test");
    }

    @Test
    void send_overlongMessageId_isRejectedBeforePersisting() {
        when(cpaRepository.findByCpaId("cpa-001")).thenReturn(Optional.of(cpa()));

        SendMessageUseCase.SendRequest request = new SendMessageUseCase.SendRequest(
                "cpa-001", "conv-001", "OrderService", "NewOrder", List.of(),
                "x".repeat(256), null, null, null, null, null);

        assertThatThrownBy(() -> service.send(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
        verify(messageRepository, never()).save(any());
    }

    @Test
    void send_withCallerSuppliedToParty_overridesTheCpa() {
        // The intermediary case: the CPA names the afnemer, the caller addresses the
        // intermediary that fronts it.
        arrangeSuccessfulSend();

        service.send(new SendMessageUseCase.SendRequest(
                "cpa-001", "conv-001", "OrderService", "NewOrder", List.of(),
                null, null, null, "urn:osb:oin:00000003342283970000", "Intermediair", null));

        EbmsMessage saved = savedMessage();
        assertThat(saved.to().partyId()).isEqualTo("urn:osb:oin:00000003342283970000");
        assertThat(saved.to().role()).isEqualTo("Intermediair");
        assertThat(saved.from().partyId()).isEqualTo("our-company");
        // The overriding id is the same kind of identifier, so it keeps the CPA's type.
        assertThat(saved.to().partyIdType()).isEqualTo("urn:example:partyIdType");
    }

    @Test
    void send_withoutParties_derivesThemFromTheCpa() {
        arrangeSuccessfulSend();

        service.send(new SendMessageUseCase.SendRequest(
                "cpa-001", "conv-001", "OrderService", "NewOrder", List.of()));

        EbmsMessage saved = savedMessage();
        assertThat(saved.from().partyId()).isEqualTo("our-company");
        assertThat(saved.to().partyId()).isEqualTo("partner-a");
    }

    @Test
    void send_roleOverrideAlone_keepsThePartyIdFromTheCpa() {
        arrangeSuccessfulSend();

        service.send(new SendMessageUseCase.SendRequest(
                "cpa-001", "conv-001", "OrderService", "NewOrder", List.of(),
                null, null, "Bronhouder", null, null, null));

        EbmsMessage saved = savedMessage();
        assertThat(saved.from().partyId()).isEqualTo("our-company");
        assertThat(saved.from().role()).isEqualTo("Bronhouder");
    }

    @Test
    void send_withRefToMessageId_persistsIt() {
        arrangeSuccessfulSend();

        service.send(new SendMessageUseCase.SendRequest(
                "cpa-001", "conv-001", "OrderService", "NewOrder", List.of(),
                null, null, null, null, null, "orig-001@partner-a.example.com"));

        assertThat(savedMessage().refToMessageId()).isEqualTo("orig-001@partner-a.example.com");
    }

    private void arrangeSuccessfulSend() {
        when(cpaRepository.findByCpaId("cpa-001")).thenReturn(Optional.of(cpa()));
        when(messageRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(serializer.serialize(any(), any()))
                .thenReturn(new SerializedMessage(new byte[]{1}, "text/xml"));
        when(transport.send(any(), any(), any()))
                .thenReturn(new TransportResult(true, new byte[0], null));
    }

    private EbmsMessage savedMessage() {
        ArgumentCaptor<EbmsMessage> captor = ArgumentCaptor.forClass(EbmsMessage.class);
        verify(messageRepository).save(captor.capture());
        return captor.getValue();
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
                new Party("our-company", "urn:example:partyIdType", null),
                new Party("partner-a", "urn:example:partyIdType", null),
                "http://partner-a.example.com/ebms/msh",
                true, true, 3, Duration.ofSeconds(60), null);
    }
}
