package dev.ebms.application.port.out;

import org.springframework.http.MediaType;

public interface MessageTransport {

    TransportResult send(String url, byte[] body, String contentType);

    record TransportResult(boolean success, byte[] responseBody, MediaType responseContentType) {}
}
