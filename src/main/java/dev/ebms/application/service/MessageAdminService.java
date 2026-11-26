package dev.ebms.application.service;

import dev.ebms.application.port.in.MessageAdminUseCase;
import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.DeliveryLog;
import dev.ebms.application.port.out.MessageSearchRepository;
import dev.ebms.application.port.out.MessageSearchRepository.Criteria;
import dev.ebms.domain.DeliveryAttempt;
import dev.ebms.domain.exception.CpaNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class MessageAdminService implements MessageAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(MessageAdminService.class);

    private final MessageSearchRepository messages;
    private final DeliveryLog deliveryLog;
    private final CpaRepository cpaRepository;
    private final Clock clock;

    @Autowired
    public MessageAdminService(MessageSearchRepository messages, DeliveryLog deliveryLog,
                               CpaRepository cpaRepository) {
        this(messages, deliveryLog, cpaRepository, Clock.systemUTC());
    }

    MessageAdminService(MessageSearchRepository messages, DeliveryLog deliveryLog,
                        CpaRepository cpaRepository, Clock clock) {
        this.messages = messages;
        this.deliveryLog = deliveryLog;
        this.cpaRepository = cpaRepository;
        this.clock = clock;
    }

    @Override
    public Page search(Criteria criteria, int page, int size) {
        return new Page(messages.search(criteria, (long) page * size, size), messages.count(criteria));
    }

    @Override
    public long count(Criteria criteria) {
        return messages.count(criteria);
    }

    @Override
    public List<DeliveryAttempt> deliveryLog(String messageId) {
        return deliveryLog.findByMessageId(messageId);
    }

    /**
     * Refuses a target CPA we do not hold, which ebms-api-service did not. It could not have:
     * it read ebms-core's tables and had no view of the CPA store. Here the store is next door,
     * and moving messages onto an agreement that does not exist makes their resend fail later,
     * far from the operator who made the mistake.
     */
    @Override
    public int moveFailedMessages(String fromCpaId, String toCpaId) {
        if (cpaRepository.findByCpaId(toCpaId).isEmpty()) {
            throw new CpaNotFoundException(toCpaId);
        }
        int moved = messages.moveFailed(fromCpaId, toCpaId);
        log.info("Moved {} failed message(s) from CPA {} to {}", moved, fromCpaId, toCpaId);
        return moved;
    }

    @Override
    public Map<String, Long> failureCounts(int minutes) {
        return deliveryLog.failureCountsByHostSince(clock.instant().minus(Duration.ofMinutes(minutes)));
    }
}
