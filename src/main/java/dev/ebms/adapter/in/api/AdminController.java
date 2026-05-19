package dev.ebms.adapter.in.api;

import dev.ebms.application.port.in.QueryMessagesUseCase;
import dev.ebms.application.port.in.SendMessageUseCase;
import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.EbmsMessage.Direction;
import dev.ebms.domain.MessageStatus;
import dev.ebms.domain.Payload;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AdminController {

    private final SendMessageUseCase sendMessageUseCase;
    private final QueryMessagesUseCase queryMessagesUseCase;
    private final CpaRepository cpaRepository;

    public AdminController(SendMessageUseCase sendMessageUseCase,
                           QueryMessagesUseCase queryMessagesUseCase,
                           CpaRepository cpaRepository) {
        this.sendMessageUseCase = sendMessageUseCase;
        this.queryMessagesUseCase = queryMessagesUseCase;
        this.cpaRepository = cpaRepository;
    }

    @PostMapping("/messages")
    public ResponseEntity<MessageResponse> submitMessage(@RequestBody SendMessageRequest request) {
        List<Payload> payloads = request.payloads().stream()
                .map(p -> new Payload(p.contentId(), p.mimeType(),
                        Base64.getDecoder().decode(p.content())))
                .toList();

        UUID id = sendMessageUseCase.send(new SendMessageUseCase.SendRequest(
                request.cpaId(), request.conversationId(), request.service(),
                request.action(), payloads));

        return queryMessagesUseCase.findById(id)
                .map(msg -> ResponseEntity.ok(toResponse(msg)))
                .orElse(ResponseEntity.internalServerError().build());
    }

    @GetMapping("/messages")
    public List<MessageResponse> listMessages(
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) MessageStatus status) {
        return queryMessagesUseCase.findAll(direction, status).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<MessageResponse> getMessage(@PathVariable UUID id) {
        return queryMessagesUseCase.findById(id)
                .map(msg -> ResponseEntity.ok(toResponse(msg)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/messages/{id}/payloads/{contentId}")
    public ResponseEntity<byte[]> downloadPayload(@PathVariable UUID id,
                                                  @PathVariable String contentId) {
        return queryMessagesUseCase.findPayload(id, contentId)
                .map(p -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, p.mimeType())
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + p.contentId() + "\"")
                        .body(p.content()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/cpas")
    public List<CpaResponse> listCpas() {
        return cpaRepository.findAll().stream().map(this::toCpaResponse).toList();
    }

    @PostMapping("/cpas/reload")
    public ResponseEntity<Void> reloadCpas() {
        cpaRepository.reload();
        return ResponseEntity.ok().build();
    }

    private MessageResponse toResponse(EbmsMessage m) {
        return new MessageResponse(m.id(), m.messageId(), m.conversationId(), m.cpaId(),
                m.from().partyId(), m.to().partyId(), m.service(), m.action(),
                m.direction().name(), m.status().name(),
                m.timestamp().toString());
    }

    private CpaResponse toCpaResponse(Cpa cpa) {
        return new CpaResponse(cpa.cpaId(), cpa.fromParty().partyId(), cpa.toParty().partyId(),
                cpa.transportUrl(), cpa.ackRequested(), cpa.retries(),
                cpa.retryInterval().getSeconds());
    }

    public record SendMessageRequest(
            String cpaId,
            String conversationId,
            String service,
            String action,
            List<PayloadRequest> payloads
    ) {
        public record PayloadRequest(String contentId, String mimeType, String content) {}
    }

    public record MessageResponse(
            UUID id, String messageId, String conversationId, String cpaId,
            String fromPartyId, String toPartyId, String service, String action,
            String direction, String status, String timestamp
    ) {}

    public record CpaResponse(
            String cpaId, String fromPartyId, String toPartyId, String transportUrl,
            boolean ackRequested, int retries, long retryIntervalSeconds
    ) {}
}
