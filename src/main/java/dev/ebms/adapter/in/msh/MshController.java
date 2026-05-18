package dev.ebms.adapter.in.msh;

import dev.ebms.application.port.in.ReceiveMessageUseCase;
import dev.ebms.application.port.out.OutboundMessageSerializer;
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
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_XML)
                    .body(buildSoapFault("InvalidMessage", e.getMessage()).getBytes());
        }

        log.info("Received message {} from {} (action: {})",
                message.messageId(), message.from().partyId(), message.action());

        Optional<EbmsMessage> ack;
        try {
            ack = receiveMessageUseCase.receive(message);
        } catch (CpaNotFoundException e) {
            log.warn("CPA not found for message {}: {}", message.messageId(), e.getMessage());
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_XML)
                    .body(buildSoapFault("UnknownCPA", e.getMessage()).getBytes());
        }

        if (ack.isPresent()) {
            OutboundMessageSerializer.SerializedMessage serialized = serializer.serialize(ack.get());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(serialized.contentType()))
                    .body(serialized.body());
        }

        return ResponseEntity.ok().build();
    }

    private String buildSoapFault(String code, String reason) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                  <SOAP-ENV:Body>
                    <SOAP-ENV:Fault>
                      <faultcode>SOAP-ENV:Client</faultcode>
                      <faultstring>%s: %s</faultstring>
                    </SOAP-ENV:Fault>
                  </SOAP-ENV:Body>
                </SOAP-ENV:Envelope>
                """.formatted(code, reason);
    }
}
