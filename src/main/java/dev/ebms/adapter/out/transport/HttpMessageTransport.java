package dev.ebms.adapter.out.transport;

import dev.ebms.application.port.out.MessageTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpMessageTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpMessageTransport.class);

    private final RestClient restClient = RestClient.create();

    @Override
    public TransportResult send(String url, byte[] body, String contentType) {
        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(body)
                    .retrieve()
                    .toEntity(byte[].class);

            boolean success = response.getStatusCode().is2xxSuccessful();
            if (!success) {
                log.warn("Remote MSH returned HTTP {}", response.getStatusCode());
            }
            return new TransportResult(success, response.getBody(), response.getHeaders().getContentType());
        } catch (RestClientException e) {
            log.warn("Transport error sending to {}: {}", url, e.getMessage());
            return new TransportResult(false, null, null);
        }
    }
}
