package dev.ebms.adapter.in.api.legacy;

import dev.ebms.adapter.in.api.legacy.LegacyApi.EbmsDeliveryLog;
import dev.ebms.adapter.in.api.legacy.LegacyApi.EbmsMessage;
import dev.ebms.adapter.in.api.legacy.LegacyApi.EbmsMessageFilter;
import dev.ebms.adapter.in.api.legacy.LegacyApi.PaginatedResponse;
import dev.ebms.application.port.in.MessageAdminUseCase;
import dev.ebms.application.port.out.MessageSearchRepository.Criteria;
import dev.ebms.application.port.out.MessageSearchRepository.Summary;
import dev.ebms.domain.DeliveryAttempt;
import dev.ebms.domain.Party;
import dev.ebms.domain.exception.CpaNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The message paths of ebms-api-service, served verbatim so that cpa-service -- and through it
 * the admin console -- needs a base-URL change and no code. ebms-api-service read ebms-core's
 * tables; this reads ours.
 *
 * <p>Times go out as local date-times, as ebms-core stored them, in the JVM's zone -- the zone
 * ebms-api-service used to read them, so the console shows the same clock it did.
 */
@RestController
@RequestMapping("/ebms/message")
public class EbmsMessageStoreController {

    /** Spring Data's own cap on a page, which ebms-api-service inherited. */
    static final int MAX_PAGE_SIZE = 2000;

    private final MessageAdminUseCase admin;
    private final ZoneId zone;

    public EbmsMessageStoreController(MessageAdminUseCase admin) {
        this.admin = admin;
        this.zone = ZoneId.systemDefault();
    }

    @PostMapping
    public ResponseEntity<PaginatedResponse<EbmsMessage>> search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestBody(required = false) EbmsMessageFilter filter) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        MessageAdminUseCase.Page result = admin.search(criteria(filter), page, size);
        List<EbmsMessage> content = result.content().stream().map(this::toWire).toList();
        int totalPages = (int) ((result.totalElements() + size - 1) / size);
        return ResponseEntity.ok(new PaginatedResponse<>(content, result.totalElements(), totalPages, page, size));
    }

    @PostMapping("/count")
    public long count(@RequestBody(required = false) EbmsMessageFilter filter) {
        return admin.count(criteria(filter));
    }

    /** An unknown id has an empty history, not a 404 -- as in ebms-api-service. */
    @GetMapping("/deliverylog")
    public List<EbmsDeliveryLog> deliveryLog(@RequestParam String messageId) {
        return admin.deliveryLog(messageId).stream().map(this::toWire).toList();
    }

    @PutMapping("/move/from/{fromCpaId}/to/{toCpaId}")
    public ResponseEntity<Void> move(@PathVariable String fromCpaId, @PathVariable String toCpaId) {
        admin.moveFailedMessages(fromCpaId, toCpaId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(CpaNotFoundException.class)
    ProblemDetail cpaNotFound(CpaNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    // ---- mapping ----

    Criteria criteria(EbmsMessageFilter filter) {
        EbmsMessageFilter f = filter != null ? filter : EbmsMessageFilter.none();
        return new Criteria(
                f.globalSearchTerm(), f.cpaId(), f.conversationId(), f.messageId(), f.refToMessageId(),
                f.fromPartyId(), f.toPartyId(),
                f.status() == null ? null : EbmsCoreStatusCode.matching(f.status()),
                startOf(f.dateFrom()),
                // dateTo is a whole day, inclusive: everything before the next day starts.
                startOf(f.dateTo() == null ? null : f.dateTo().plusDays(1)));
    }

    private Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(zone).toInstant();
    }

    private EbmsMessage toWire(Summary summary) {
        dev.ebms.domain.EbmsMessage m = summary.message();
        return new EbmsMessage(
                local(m.timestamp()),
                m.cpaId(),
                m.conversationId(),
                m.messageId(),
                m.refToMessageId(),
                null,
                null,
                qualified(m.from()),
                m.from().role(),
                qualified(m.to()),
                m.to().role(),
                m.service(),
                m.action(),
                null,
                EbmsCoreStatusCode.of(m),
                local(summary.statusTime()));
    }

    private EbmsDeliveryLog toWire(DeliveryAttempt attempt) {
        return new EbmsDeliveryLog(
                attempt.messageId(),
                local(attempt.attemptedAt()),
                attempt.uri(),
                attempt.outcome().code(),
                // ebms-api-service turned a null error into "", and the console may rely on it.
                attempt.errorMessage() == null ? "" : attempt.errorMessage());
    }

    private LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, zone);
    }

    /**
     * A party id the way ebms-core stored it: {@code type:id}. Inbound messages keep the bare
     * {@code eb:PartyId} text and its type apart; an id that already carries its URN -- what our
     * clients send outbound -- goes out as it is.
     */
    static String qualified(Party party) {
        String id = party.partyId();
        String type = party.partyIdType();
        if (id == null || type == null || type.isBlank() || id.startsWith(type + ":")) {
            return id;
        }
        return type + ":" + id;
    }
}
