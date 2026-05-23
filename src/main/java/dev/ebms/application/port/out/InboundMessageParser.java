package dev.ebms.application.port.out;

import dev.ebms.domain.EbmsMessage;

import java.util.Optional;

public interface InboundMessageParser {

    /** Parses body as an ebMS message. Returns empty if body is blank or not a valid ebMS message. */
    Optional<EbmsMessage> tryParse(byte[] body, String contentType);
}
