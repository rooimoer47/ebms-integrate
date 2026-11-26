package dev.ebms.application.port.out;

import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.Objects;

public interface MessageTransport {

    TransportResult send(String url, byte[] body, String contentType);

    /**
     * @param error why the attempt failed, as the delivery log shows it; null on success
     */
    record TransportResult(boolean success, byte[] responseBody, MediaType responseContentType,
                           String error) {

        public TransportResult(boolean success, byte[] responseBody, MediaType responseContentType) {
            this(success, responseBody, responseContentType, null);
        }

        public static TransportResult failed(String error) {
            return new TransportResult(false, null, null, error);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof TransportResult(var s, var rb, var rct, var err)
                    && success == s
                    && Arrays.equals(responseBody, rb)
                    && Objects.equals(responseContentType, rct)
                    && Objects.equals(error, err);
        }

        @Override
        public int hashCode() {
            return Objects.hash(success, Arrays.hashCode(responseBody), responseContentType, error);
        }

        @Override
        public String toString() {
            return "TransportResult[success=" + success + ", responseBody=" + Arrays.toString(responseBody)
                    + ", responseContentType=" + responseContentType + ", error=" + error + "]";
        }
    }
}
