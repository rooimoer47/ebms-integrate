package dev.ebms.application.port.in;

import dev.ebms.domain.EbmsMessage;

import java.util.Optional;

public interface ReceiveMessageUseCase {

    /**
     * Process an inbound ebMS message. Returns an acknowledgment message when
     * the sender requested one (AckRequested header present), empty otherwise.
     * Duplicate messages are detected and re-acked without reprocessing.
     */
    Optional<EbmsMessage> receive(EbmsMessage message);
}
