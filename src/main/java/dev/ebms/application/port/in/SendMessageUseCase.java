package dev.ebms.application.port.in;

import dev.ebms.domain.Payload;

import java.util.List;
import java.util.UUID;

public interface SendMessageUseCase {

    UUID send(SendRequest request);

    record SendRequest(
            String cpaId,
            String conversationId,
            String service,
            String action,
            List<Payload> payloads
    ) {}
}
