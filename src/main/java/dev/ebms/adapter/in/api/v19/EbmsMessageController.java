package dev.ebms.adapter.in.api.v19;

import dev.ebms.application.port.in.QueryMessagesUseCase;
import dev.ebms.application.port.in.SendMessageUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ebms-core v19 message API, which jms-producer and jms-consumer call unmodified.
 *
 * <p>The path is fixed rather than configurable: it is the one the clients ship as their
 * default ({@code http://ebms-core:8888/service/rest/v19/ebms/messages}), so matching it means
 * the Helm values need no path of their own.
 *
 * <p>One mapping serves both callers of the submit. jms-consumer points
 * {@code ebms.message-service.url} straight at this collection and posts to it, while
 * cpa-service points {@code ebms.rest-service.url} one level up and posts to
 * {@code /messages} -- the same URL by two routes.
 */
@RestController
@RequestMapping("/service/rest/v19/ebms/messages")
public class EbmsMessageController {

    private static final Logger log = LoggerFactory.getLogger(EbmsMessageController.class);

    private final QueryMessagesUseCase queryMessagesUseCase;
    private final SendMessageUseCase sendMessageUseCase;

    public EbmsMessageController(QueryMessagesUseCase queryMessagesUseCase,
                                 SendMessageUseCase sendMessageUseCase) {
        this.queryMessagesUseCase = queryMessagesUseCase;
        this.sendMessageUseCase = sendMessageUseCase;
    }

    /**
     * Submit a message for sending, answering with its ebMS message id.
     *
     * <p>The response is {@code text/plain} carrying the bare id, not JSON: the clients read it
     * into a {@code String}, so a JSON envelope would be stored as the message id itself.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> sendMessage(@RequestBody V19MessageRequest request) {
        SendMessageUseCase.SendResult result = sendMessageUseCase.send(request.toSendRequest());
        log.info("Accepted message {} for cpa {}", result.messageId(),
                request.properties() != null ? request.properties().cpaId() : null);
        return ResponseEntity.ok(result.messageId());
    }

    /**
     * Resend an existing message's content as a new ebMS message, answering with the new id.
     *
     * <p>jms-consumer's {@code Resender} drives this from the {@code FAILED} and
     * {@code EXPIRED} queues and treats a 404 as "drop it from the queue", so an unknown id
     * must not come back as anything else.
     */
    @PutMapping(value = "/{messageId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> resendMessage(@PathVariable String messageId) {
        return sendMessageUseCase.resend(messageId)
                .map(result -> {
                    log.info("Resent {} as {}", messageId, result.messageId());
                    return ResponseEntity.ok(result.messageId());
                })
                .orElseGet(() -> {
                    log.info("Cannot resend: no message with id {}", messageId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Return a message and, when {@code process} is true, mark it processed so that it stops
     * appearing as unprocessed. jms-producer calls this once per {@code RECEIVED} event with
     * {@code process=true}, so the read and the flag have to commit together.
     */
    @GetMapping("/{messageId}")
    public ResponseEntity<V19Message> getMessage(
            @PathVariable String messageId,
            @RequestParam(defaultValue = "false") boolean process) {

        // jms-producer URL-encodes the id, so '@' arrives as %40. Spring MVC decodes path
        // variables for us -- decoding again here would turn a literal '+' into a space.
        return queryMessagesUseCase.fetchByMessageId(messageId, process)
                .map(message -> {
                    if (process) {
                        log.debug("Message {} fetched and marked processed", messageId);
                    }
                    return ResponseEntity.ok(V19Message.from(message));
                })
                .orElseGet(() -> {
                    log.info("No message found for id {}", messageId);
                    return ResponseEntity.notFound().build();
                });
    }
}
