package dev.ebms.adapter.out.transport;

import dev.ebms.application.port.out.MessageTransport;
import dev.ebms.config.EbmsSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

@Component
public class HttpMessageTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpMessageTransport.class);

    private final RestClient restClient;

    public HttpMessageTransport(RestClient.Builder restClientBuilder, EbmsSecurityProperties props) {
        if (props.keystoreConfigured()) {
            try {
                SSLContext sslContext = buildSslContext(props);
                HttpClient httpClient = HttpClient.newBuilder()
                        .sslContext(sslContext)
                        .build();
                this.restClient = restClientBuilder
                        .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                        .build();
                log.info("Outbound transport configured with mTLS client certificate");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to configure mTLS for outbound transport", e);
            }
        } else {
            this.restClient = restClientBuilder.build();
            log.info("Outbound transport running without mTLS (no keystore configured)");
        }
    }

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

    private static SSLContext buildSslContext(EbmsSecurityProperties props) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(props.getKeystorePath())) {
            keyStore.load(fis, props.getKeystorePassword().toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, props.getKeystorePassword().toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (props.truststoreConfigured()) {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(props.getTruststorePath())) {
                trustStore.load(fis, props.getTruststorePassword().toCharArray());
            }
            tmf.init(trustStore);
        } else {
            tmf.init((KeyStore) null);  // JVM default trust store
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }
}
