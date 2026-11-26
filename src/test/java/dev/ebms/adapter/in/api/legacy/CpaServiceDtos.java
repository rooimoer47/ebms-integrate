package dev.ebms.adapter.in.api.legacy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * cpa-service's own copies of ebms-api-service's shapes, field for field, from
 * {@code cpa-service/src/main/java/nl/logius/osdbk/cpa/model/dto/}. These are what our responses
 * must deserialise into and what our filter must accept; if cpa-service changes one, change it
 * here and let the contract test say whether we still fit.
 */
final class CpaServiceDtos {

    private CpaServiceDtos() {
    }

    /** {@code model.dto.external.EbmsMessage} */
    record EbmsMessage(
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

    /** {@code model.dto.external.PaginatedResponse} */
    record PaginatedResponse<T>(
            List<T> content,
            long totalElements,
            int totalPages,
            int currentPage,
            int pageSize) {
    }

    /** {@code model.dto.external.EbmsDeliveryLog.EbmsDeliveryLogEntry} */
    record EbmsDeliveryLogEntry(
            String messageId,
            LocalDateTime timestamp,
            String uri,
            Short status,
            String errorMessage) {
    }

    /** {@code model.dto.EbmsMessageFilterDto}, which cpa-service posts as the search body. */
    record EbmsMessageFilterDto(
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

        static EbmsMessageFilterDto none() {
            return new EbmsMessageFilterDto(null, null, null, null, null, null, null, null, null, null);
        }

        EbmsMessageFilterDto withCpaId(String v) {
            return new EbmsMessageFilterDto(globalSearchTerm, v, conversationId, messageId, refToMessageId, fromPartyId, toPartyId, status, dateFrom, dateTo);
        }

        EbmsMessageFilterDto withMessageId(String v) {
            return new EbmsMessageFilterDto(globalSearchTerm, cpaId, conversationId, v, refToMessageId, fromPartyId, toPartyId, status, dateFrom, dateTo);
        }

        EbmsMessageFilterDto withFromPartyId(String v) {
            return new EbmsMessageFilterDto(globalSearchTerm, cpaId, conversationId, messageId, refToMessageId, v, toPartyId, status, dateFrom, dateTo);
        }

        EbmsMessageFilterDto withToPartyId(String v) {
            return new EbmsMessageFilterDto(globalSearchTerm, cpaId, conversationId, messageId, refToMessageId, fromPartyId, v, status, dateFrom, dateTo);
        }

        EbmsMessageFilterDto withStatus(int v) {
            return new EbmsMessageFilterDto(globalSearchTerm, cpaId, conversationId, messageId, refToMessageId, fromPartyId, toPartyId, (short) v, dateFrom, dateTo);
        }

        EbmsMessageFilterDto withGlobalSearchTerm(String v) {
            return new EbmsMessageFilterDto(v, cpaId, conversationId, messageId, refToMessageId, fromPartyId, toPartyId, status, dateFrom, dateTo);
        }

        EbmsMessageFilterDto withDates(LocalDate from, LocalDate to) {
            return new EbmsMessageFilterDto(globalSearchTerm, cpaId, conversationId, messageId, refToMessageId, fromPartyId, toPartyId, status, from, to);
        }
    }
}
