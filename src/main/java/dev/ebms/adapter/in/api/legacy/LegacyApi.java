package dev.ebms.adapter.in.api.legacy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The wire shapes of ebms-api-service, kept field for field. cpa-service deserialises them into
 * its own copies ({@code nl.logius.osdbk.cpa.model.dto.external.*}), so names, order and types
 * are load-bearing; {@code LegacyApiContractTest} holds them against those copies.
 */
public final class LegacyApi {

    private LegacyApi() {
    }

    /** The console's search filter. Every field is optional. {@code status} is ebms-core's number. */
    public record EbmsMessageFilter(
            String globalSearchTerm,
            String cpaId,
            String conversationId,
            String messageId,
            String refToMessageId,
            String fromPartyId,
            String toPartyId,
            Short status,
            LocalDate dateFrom,
            LocalDate dateTo) {

        static EbmsMessageFilter none() {
            return new EbmsMessageFilter(null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * One message as the console lists it. {@code timeToLive} and {@code persistTime} are always
     * null -- we model neither -- and so is {@code content}, which in ebms-core held the whole
     * SOAP envelope; we keep the parsed header, not the envelope.
     */
    public record EbmsMessage(
            LocalDateTime timestamp,
            String cpaId,
            String conversationId,
            String messageId,
            String refToMessageId,
            LocalDateTime timeToLive,
            LocalDateTime persistTime,
            String fromPartyId,
            String fromRole,
            String toPartyId,
            String toRole,
            String service,
            String action,
            String content,
            Short status,
            LocalDateTime statusTime) {
    }

    public record PaginatedResponse<T>(
            List<T> content,
            long totalElements,
            int totalPages,
            int currentPage,
            int pageSize) {
    }

    /** {@code status} is ebms-core's DeliveryTaskStatus: 1 succeeded, 2 failed, 3 expired. */
    public record EbmsDeliveryLog(
            String messageId,
            LocalDateTime timestamp,
            String uri,
            Short status,
            String errorMessage) {
    }
}
