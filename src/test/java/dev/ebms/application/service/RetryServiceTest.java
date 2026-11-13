package dev.ebms.application.service;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Party;
import dev.ebms.domain.Payload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock CpaRepository cpaRepository;
    @Mock SendMessageService sendMessageService;

    RetryService service;

    @BeforeEach
    void setup() {
        service = new RetryService(messageRepository, cpaRepository, sendMessageService,
                new SimpleMeterRegistry());
    }

    @Test
    void retryPending_loadsEachMessageIndividuallyWithItsPayloads() {
        // The whole point of the id-based query: a retried message must still carry its
        // content, but the batch must not sit in the heap all at once. If someone "optimises"
        // this back into one query returning payload-free messages, the serialiser would
        // happily send an ebMS message with no Manifest and this test would catch it.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(messageRepository.findPendingRetryIds(any())).thenReturn(List.of(first, second));
        when(messageRepository.findById(first)).thenReturn(Optional.of(pending(first)));
        when(messageRepository.findById(second)).thenReturn(Optional.of(pending(second)));
        when(cpaRepository.findByCpaId("cpa-001")).thenReturn(Optional.of(cpa()));

        service.retryPending();

        verify(messageRepository).findById(first);
        verify(messageRepository).findById(second);

        ArgumentCaptor<EbmsMessage> sent = ArgumentCaptor.forClass(EbmsMessage.class);
        verify(sendMessageService, org.mockito.Mockito.times(2))
                .attemptSend(sent.capture(), any());
        assertThat(sent.getAllValues())
                .allSatisfy(message -> assertThat(message.payloads()).isNotEmpty());
    }

    @Test
    void retryPending_nothingPending_touchesNothingElse() {
        when(messageRepository.findPendingRetryIds(any())).thenReturn(List.of());

        service.retryPending();

        verify(messageRepository, never()).findById(any());
        verifyNoInteractions(cpaRepository, sendMessageService);
    }

    @Test
    void retryPending_messageVanishedBetweenQueryAndFetch_isSkipped() {
        // The id list and the fetch are separate reads, so a message can be gone by the time
        // we get to it.
        UUID id = UUID.randomUUID();
        when(messageRepository.findPendingRetryIds(any())).thenReturn(List.of(id));
        when(messageRepository.findById(id)).thenReturn(Optional.empty());

        service.retryPending();

        verifyNoInteractions(sendMessageService);
    }

    private EbmsMessage pending(UUID id) {
        EbmsMessage message = EbmsMessage.newOutbound(
                id + "@msh.test", "conv-001", "cpa-001",
                new Party("urn:osb:oin:00000001822477348001", "urn:osb:oin", "Bronhouder"),
                new Party("urn:osb:oin:00000001810220350000", "urn:osb:oin", "Afnemer"),
                "urn:osb:services:verstrekking", "verstrekkingDoorLV",
                List.of(new Payload("payload-1", "text/xml",
                        "<order/>".getBytes(StandardCharsets.UTF_8))),
                true);
        return message;
    }

    private Cpa cpa() {
        return new Cpa("cpa-001",
                new Party("urn:osb:oin:00000001822477348001", "urn:osb:oin", "Bronhouder"),
                new Party("urn:osb:oin:00000001810220350000", "urn:osb:oin", "Afnemer"),
                "http://partner.example.nl/ebms/msh",
                true, true, 3, Duration.ofSeconds(60), null);
    }
}
