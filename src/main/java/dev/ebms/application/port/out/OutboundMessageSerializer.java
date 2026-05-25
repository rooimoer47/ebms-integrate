package dev.ebms.application.port.out;

import dev.ebms.domain.EbmsMessage;

import java.security.cert.X509Certificate;

public interface OutboundMessageSerializer {

    /** Serialize message for sending. Pass recipientCert to enable payload encryption; null disables it. */
    SerializedMessage serialize(EbmsMessage message, X509Certificate recipientCert);

    SerializedMessage serializeError(EbmsMessage context, String errorCode, String description);

    record SerializedMessage(byte[] body, String contentType) {}
}
