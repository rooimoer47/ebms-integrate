package dev.ebms.application.port.out;

import dev.ebms.domain.EbmsMessage;

public interface OutboundMessageSerializer {

    SerializedMessage serialize(EbmsMessage message);

    SerializedMessage serializeError(EbmsMessage context, String errorCode, String description);

    record SerializedMessage(byte[] body, String contentType) {}
}
