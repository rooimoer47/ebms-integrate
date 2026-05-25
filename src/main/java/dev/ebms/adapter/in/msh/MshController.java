package dev.ebms.adapter.in.msh;

import dev.ebms.application.port.in.ReceiveMessageUseCase;
import dev.ebms.application.port.out.OutboundMessageSerializer;
import dev.ebms.application.port.out.OutboundMessageSerializer.SerializedMessage;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.exception.CpaNotFoundException;
import dev.ebms.domain.exception.MessageParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class MshController {

    private static final Logger log = LoggerFactory.getLogger(MshController.class);

    private final SoapMimeParser parser;
    private final OutboundMessageSerializer serializer;
    private final ReceiveMessageUseCase receiveMessageUseCase;

    public MshController(SoapMimeParser parser, OutboundMessageSerializer serializer,
                         ReceiveMessageUseCase receiveMessageUseCase) {
        this.parser = parser;
        this.serializer = serializer;
        this.receiveMessageUseCase = receiveMessageUseCase;
    }

    @PostMapping("/ebms/msh")
    public ResponseEntity<byte[]> receive(HttpEntity<byte[]> entity) {
        String contentType = Optional.ofNullable(entity.getHeaders().getContentType())
                .map(MediaType::toString)
                .orElse("text/xml");

        EbmsMessage message;
        try {
            message = parser.parse(entity.getBody(), contentType);
        } catch (MessageParseException e) {
            log.warn("Failed to parse inbound message: {}", e.getMessage());
            SerializedMessage error = serializer.serializeError(null, e.getEbmsErrorCode(), e.getMessage());
            return ResponseEntity.badRequest()
                    .contentType(MediaType.parseMediaType(error.contentType()))
                    .body(error.body());
        }

        log.info("Received message {} from {} (action: {})",
                message.messageId(), message.from().partyId(), message.action());

        Optional<EbmsMessage> ack;
        try {
            ack = receiveMessageUseCase.receive(message);
        } catch (CpaNotFoundException e) {
            log.warn("CPA not found for message {}: {}", message.messageId(), e.getMessage());
            SerializedMessage error = serializer.serializeError(message, "ValueNotRecognized", e.getMessage());
            return ResponseEntity.badRequest()
                    .contentType(MediaType.parseMediaType(error.contentType()))
                    .body(error.body());
        }

        if (ack.isPresent()) {
            OutboundMessageSerializer.SerializedMessage serialized = serializer.serialize(ack.get(), null);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(serialized.contentType()))
                    .body(serialized.body());
        }

        return ResponseEntity.ok().build();
    }

}
