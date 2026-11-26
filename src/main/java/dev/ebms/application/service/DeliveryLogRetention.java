package dev.ebms.application.service;

import dev.ebms.application.port.out.DeliveryLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * Keeps the delivery log bounded. It gains a row per send attempt, retries included, and nothing
 * else ever deletes from it. The throttling window is minutes and an operator investigating a
 * failure looks back days, so the default of 30 days keeps everything either of them uses.
 */
@Service
public class DeliveryLogRetention {

    private static final Logger log = LoggerFactory.getLogger(DeliveryLogRetention.class);

    private final DeliveryLog deliveryLog;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public DeliveryLogRetention(DeliveryLog deliveryLog,
                                @Value("${ebms.delivery-log.retention:30d}") Duration retention) {
        this(deliveryLog, retention, Clock.systemUTC());
    }

    DeliveryLogRetention(DeliveryLog deliveryLog, Duration retention, Clock clock) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalStateException("ebms.delivery-log.retention must be positive, was " + retention);
        }
        this.deliveryLog = deliveryLog;
        this.retention = retention;
        this.clock = clock;
    }

    /** Hourly, so a purge is small and never holds a long lock on the table the send path writes. */
    @Scheduled(cron = "${ebms.delivery-log.purge-cron:0 17 * * * *}")
    public int purge() {
        int purged = deliveryLog.purgeOlderThan(clock.instant().minus(retention));
        if (purged > 0) {
            log.info("Purged {} delivery log row(s) older than {}", purged, retention);
        }
        return purged;
    }
}
