package dev.ebms.application.service;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.DeliveryLog;
import dev.ebms.application.port.out.MessageSearchRepository;
import dev.ebms.application.port.out.MessageSearchRepository.Criteria;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.exception.CpaNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageAdminServiceTest {

    final MessageSearchRepository messages = mock(MessageSearchRepository.class);
    final DeliveryLog deliveryLog = mock(DeliveryLog.class);
    final CpaRepository cpas = mock(CpaRepository.class);
    final Instant now = Instant.parse("2026-11-26T12:00:00Z");
    final MessageAdminService service = new MessageAdminService(messages, deliveryLog, cpas,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void search_turnsAPageNumberIntoAnOffset() {
        when(messages.search(Criteria.none(), 40, 20)).thenReturn(List.of());
        when(messages.count(Criteria.none())).thenReturn(45L);

        assertThat(service.search(Criteria.none(), 2, 20).totalElements()).isEqualTo(45);
        verify(messages).search(Criteria.none(), 40, 20);
    }

    @Test
    void failureCounts_lookBackTheRequestedMinutes() {
        when(deliveryLog.failureCountsByHostSince(Instant.parse("2026-11-26T11:55:00Z")))
                .thenReturn(Map.of("afnemer.example.nl", 3L));

        assertThat(service.failureCounts(5)).containsEntry("afnemer.example.nl", 3L);
    }

    @Test
    void move_toAnUnknownCpa_isRefusedBeforeAnythingMoves() {
        when(cpas.findByCpaId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveFailedMessages("old", "nope"))
                .isInstanceOf(CpaNotFoundException.class);
        verify(messages, never()).moveFailed(any(), any());
    }

    @Test
    void move_toAKnownCpa_reportsHowManyMoved() {
        when(cpas.findByCpaId("new")).thenReturn(Optional.of(mock(Cpa.class)));
        when(messages.moveFailed("old", "new")).thenReturn(2);

        assertThat(service.moveFailedMessages("old", "new")).isEqualTo(2);
    }
}
