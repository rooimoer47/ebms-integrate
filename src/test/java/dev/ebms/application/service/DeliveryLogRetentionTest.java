package dev.ebms.application.service;

import dev.ebms.application.port.out.DeliveryLog;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryLogRetentionTest {

    final Instant now = Instant.parse("2026-11-26T12:00:00Z");
    final DeliveryLog deliveryLog = mock(DeliveryLog.class);

    @Test
    void purge_cutsOffAtNowMinusTheRetention() {
        when(deliveryLog.purgeOlderThan(Instant.parse("2026-10-27T12:00:00Z"))).thenReturn(4);

        int purged = new DeliveryLogRetention(deliveryLog, Duration.ofDays(30),
                Clock.fixed(now, ZoneOffset.UTC)).purge();

        assertThat(purged).isEqualTo(4);
        verify(deliveryLog).purgeOlderThan(Instant.parse("2026-10-27T12:00:00Z"));
    }

    /** A zero retention would delete every attempt the moment it was written, throttling included. */
    @Test
    void aRetentionThatIsNotPositive_failsStartup() {
        assertThatThrownBy(() -> new DeliveryLogRetention(deliveryLog, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ebms.delivery-log.retention");
    }
}
