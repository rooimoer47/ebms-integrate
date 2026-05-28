package dev.ebms.application.port.out;

import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.Objects;

public interface MessageTransport {

    TransportResult send(String url, byte[] body, String contentType);

    record TransportResult(boolean success, byte[] responseBody, MediaType responseContentType) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            return o instanceof TransportResult(var s, var rb, var rct)
                    && success == s
                    && Arrays.equals(responseBody, rb)
                    && Objects.equals(responseContentType, rct);
        }

        @Override
        public int hashCode() {
            return Objects.hash(success, Arrays.hashCode(responseBody), responseContentType);
        }

        @Override
        public String toString() {
            return "TransportResult[success=" + success + ", responseBody=" + Arrays.toString(responseBody)
                    + ", responseContentType=" + responseContentType + "]";
        }
    }
}
