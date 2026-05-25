package dev.ebms.adapter.in.api;

import dev.ebms.application.port.in.SendMessageUseCase;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Payload;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Drop-in shim matching the ebms-core REST API so existing ebms-core clients can switch to this MSH
 * without changing their integration code. Maps to the same use cases as AdminController.
 */
@RestController
@RequestMapping("/ebms-core")
public class EbmsCompatController {

    private final SendMessageUseCase sendMessageUseCase;
    private final MessageRepository messageRepository;

    public EbmsCompatController(SendMessageUseCase sendMessageUseCase,
                                 MessageRepository messageRepository) {
        this.sendMessageUseCase = sendMessageUseCase;
        this.messageRepository = messageRepository;
    }

    /** Submit a message — mirrors ebms-core POST /message */
    @PostMapping("/message")
    public ResponseEntity<MessageStatusResponse> sendMessage(
            @RequestBody EbmsCompatMessageRequest request) {
        List<Payload> payloads = request.dataSources().stream()
                .map(ds -> new Payload(ds.contentId(), ds.mimeType(),
                        Base64.getDecoder().decode(ds.content())))
                .toList();

        UUID id = sendMessageUseCase.send(new SendMessageUseCase.SendRequest(
                request.cpaId(), request.conversationId(), request.service(),
                request.action(), payloads));

        return messageRepository.findById(id)
                .map(msg -> ResponseEntity.ok(toStatusResponse(msg)))
                .orElse(ResponseEntity.internalServerError().build());
    }

    /** Retrieve unprocessed inbound messages — mirrors ebms-core GET /unprocessedMessages */
    @GetMapping("/unprocessedMessages")
    public List<MessageStatusResponse> getUnprocessedMessages() {
        return messageRepository.findUnprocessedInbound().stream()
                .map(this::toStatusResponse)
                .toList();
    }

    /** Mark a message as processed — mirrors ebms-core POST /processedMessage/{messageId} */
    @PostMapping("/processedMessage/{messageId}")
    public ResponseEntity<Void> markProcessed(@PathVariable String messageId) {
        return messageRepository.findByMessageId(messageId)
                .map(msg -> {
                    messageRepository.markProcessed(msg.id());
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** Get message status — mirrors ebms-core GET /messageStatus/{messageId} */
    @GetMapping("/messageStatus/{messageId}")
    public ResponseEntity<MessageStatusResponse> getMessageStatus(@PathVariable String messageId) {
        return messageRepository.findByMessageId(messageId)
                .map(msg -> ResponseEntity.ok(toStatusResponse(msg)))
                .orElse(ResponseEntity.notFound().build());
    }

    private MessageStatusResponse toStatusResponse(EbmsMessage msg) {
        return new MessageStatusResponse(
                msg.messageId(), msg.conversationId(), msg.cpaId(),
                msg.from().partyId(), msg.to().partyId(),
                msg.service(), msg.action(),
                msg.status().name(), msg.timestamp().toString(),
                msg.processed()
        );
    }

    public record EbmsCompatMessageRequest(
            String cpaId,
            String conversationId,
            String service,
            String action,
            List<DataSource> dataSources
    ) {
        public record DataSource(String contentId, String mimeType, String content) {}
    }

    public record MessageStatusResponse(
            String messageId,
            String conversationId,
            String cpaId,
            String fromPartyId,
            String toPartyId,
            String service,
            String action,
            String status,
            String timestamp,
            boolean processed
    ) {}
}
