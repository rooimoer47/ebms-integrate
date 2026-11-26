package dev.ebms.application.port.out;

import dev.ebms.domain.DeliveryAttempt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Per-attempt delivery history. */
public interface DeliveryLog {

    /**
     * Records an attempt in its own transaction. An attempt happened whether or not the work
     * around it commits, and a failure to record it must not undo the send it describes.
     */
    void record(DeliveryAttempt attempt);

    /** Oldest first, the order an operator reads a history in. */
    List<DeliveryAttempt> findByMessageId(String messageId);

    /**
     * Failed attempts since {@code since}, counted per host of the URI they went to. Per host,
     * not per URL: that is the key ebms-api-service returned and the one jms-consumer throttles
     * an afnemer on.
     */
    Map<String, Long> failureCountsByHostSince(Instant since);

    /** Deletes attempts older than {@code cutoff}; returns how many. */
    int purgeOlderThan(Instant cutoff);
}
