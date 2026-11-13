package dev.ebms.adapter.in.api.v19;

import dev.ebms.domain.exception.CpaNotFoundException;
import dev.ebms.domain.exception.InvalidCpaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error responses for the v19 API. Scoped to this package so that the {@code /api/**} admin
 * surface keeps Spring's defaults.
 *
 * <p>Bodies are {@code text/plain}, matching the success responses, and they carry the reason
 * rather than a generic error envelope. That is not cosmetic: a client decides what to do next
 * by reading the text.
 */
@RestControllerAdvice(basePackageClasses = EbmsMessageController.class)
public class V19ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(V19ExceptionHandler.class);

    /**
     * A resubmission of an existing message id.
     *
     * <p>The body has to contain the driver's own text, because
     * {@code JmsMessageConsumer.isDuplicateMessageId()} decides whether this was a benign JMS
     * redelivery by looking for {@code duplicate key value violates unique constraint} and the
     * constraint name {@code uc_ebms_message} in it. Spring's default error body would omit the
     * message entirely, the check would fail, and the consumer would retry forever on a message
     * that can never succeed.
     *
     * <p>Only the most specific cause is returned: the full exception chain includes the SQL
     * statement and would be truncated by the client before the constraint name is reached.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> onDataIntegrityViolation(DataIntegrityViolationException e) {
        String cause = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        log.warn("Rejected message: {}", cause);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.TEXT_PLAIN)
                .body(cause);
    }

    @ExceptionHandler(CpaNotFoundException.class)
    public ResponseEntity<String> onCpaNotFound(CpaNotFoundException e) {
        log.warn("Rejected message: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(e.getMessage());
    }

    /**
     * A CPA that cannot be stored, with the reason in the body.
     *
     * <p>The body is the whole answer here: cpa-service turns any 4xx or 5xx into "CPA is
     * invalid" and shows an operator our text, so a status code alone tells them nothing.
     * ebms-core answers 500 for this, from an exception its own handler does not special-case.
     */
    @ExceptionHandler(InvalidCpaException.class)
    public ResponseEntity<String> onInvalidCpa(InvalidCpaException e) {
        log.warn("Rejected CPA: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(e.getMessage());
    }

    /** Caller-supplied values the domain refuses, such as an over-long message id. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> onIllegalArgument(IllegalArgumentException e) {
        log.warn("Rejected message: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(e.getMessage());
    }
}
