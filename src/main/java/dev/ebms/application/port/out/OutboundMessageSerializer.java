package dev.ebms.application.port.out;

import dev.ebms.domain.EbmsMessage;

public interface OutboundMessageSerializer {

    SerializedMessage serialize(EbmsMessage message);

    record SerializedMessage(byte[] body, String contentType) {}
}
