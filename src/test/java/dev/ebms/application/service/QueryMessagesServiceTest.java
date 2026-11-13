package dev.ebms.application.service;

import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Party;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryMessagesServiceTest {

    private static final String MESSAGE_ID = "AFN-9001@00000001810220350000";

    @Mock
    MessageRepository messageRepository;

    QueryMessagesService service;

    @BeforeEach
    void setup() {
        service = new QueryMessagesService(messageRepository);
    }

    @Test
    void fetchByMessageId_withProcessing_marksTheMessageProcessed() {
        EbmsMessage message = inbound(false);
        when(messageRepository.findByMessageId(MESSAGE_ID)).thenReturn(Optional.of(message));

        Optional<EbmsMessage> result = service.fetchByMessageId(MESSAGE_ID, true);

        assertThat(result).isPresent();
        verify(messageRepository).markProcessed(message.id());
    }

    @Test
    void fetchByMessageId_withoutProcessing_leavesTheFlagAlone() {
        when(messageRepository.findByMessageId(MESSAGE_ID))
                .thenReturn(Optional.of(inbound(false)));

        service.fetchByMessageId(MESSAGE_ID, false);

        verify(messageRepository, never()).markProcessed(any());
    }

    @Test
    void fetchByMessageId_alreadyProcessed_doesNotMarkItAgain() {
        // A redelivered RECEIVED event asks for the same message twice; the second fetch
        // should still return it, but there is nothing left to flip.
        when(messageRepository.findByMessageId(MESSAGE_ID))
                .thenReturn(Optional.of(inbound(true)));

        Optional<EbmsMessage> result = service.fetchByMessageId(MESSAGE_ID, true);

        assertThat(result).isPresent();
        verify(messageRepository, never()).markProcessed(any());
    }

    @Test
    void fetchByMessageId_unknownId_isEmptyAndMarksNothing() {
        when(messageRepository.findByMessageId("nope")).thenReturn(Optional.empty());

        assertThat(service.fetchByMessageId("nope", true)).isEmpty();
        verify(messageRepository, never()).markProcessed(any());
    }

    private EbmsMessage inbound(boolean processed) {
        EbmsMessage message = EbmsMessage.newInbound(
                MESSAGE_ID, "conv-001", "cpa-001",
                new Party("urn:osb:oin:00000001822477348001", "urn:osb:oin", "Bronhouder"),
                new Party("urn:osb:oin:00000001810220350000", "urn:osb:oin", "Afnemer"),
                "urn:osb:services:verstrekking", "verstrekkingDoorLV",
                Instant.parse("2026-11-13T09:15:30Z"), List.of(), false, null);
        return processed ? message.withProcessed(true) : message;
    }
}
