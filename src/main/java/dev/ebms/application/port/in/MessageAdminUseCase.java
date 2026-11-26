package dev.ebms.application.port.in;

import dev.ebms.application.port.out.MessageSearchRepository.Criteria;
import dev.ebms.application.port.out.MessageSearchRepository.Summary;
import dev.ebms.domain.DeliveryAttempt;

import java.util.List;
import java.util.Map;

/**
 * What the admin console and jms-consumer's throttling read from the message store -- the job
 * ebms-api-service did against ebms-core's tables before we absorbed it.
 */
public interface MessageAdminUseCase {

    record Page(List<Summary> content, long totalElements) {
    }

    Page search(Criteria criteria, int page, int size);

    long count(Criteria criteria);

    List<DeliveryAttempt> deliveryLog(String messageId);

    /** @throws dev.ebms.domain.exception.CpaNotFoundException if {@code toCpaId} is not in the store */
    int moveFailedMessages(String fromCpaId, String toCpaId);

    /** Failed delivery attempts per host over the last {@code minutes}. */
    Map<String, Long> failureCounts(int minutes);
}
