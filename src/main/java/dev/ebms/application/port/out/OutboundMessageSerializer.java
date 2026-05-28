package dev.ebms.application.port.out;

import dev.ebms.domain.EbmsMessage;

import java.security.cert.X509Certificate;
import java.util.Arrays;

public interface OutboundMessageSerializer {

    /** Serialize message for sending. Pass recipientCert to enable payload encryption; null disables it. */
    SerializedMessage serialize(EbmsMessage message, X509Certificate recipientCert);

    SerializedMessage serializeError(EbmsMessage context, String errorCode, String description);

    record SerializedMessage(byte[] body, String contentType) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof SerializedMessage(var b, var ct)
                    && Arrays.equals(body, b)
                    && java.util.Objects.equals(contentType, ct);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(Arrays.hashCode(body), contentType);
        }

        @Override
        public String toString() {
            return "SerializedMessage[body=" + Arrays.toString(body) + ", contentType=" + contentType + "]";
        }
    }
}
