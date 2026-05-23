package dev.ebms.adapter.out.transport;

import dev.ebms.config.EbmsSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThatNoException;

class HttpMessageTransportMtlsTest {

    @Test
    void constructor_withKeystoreConfigured_buildsMtlsClient() {
        URL ks = HttpMessageTransportMtlsTest.class.getResource("/test-keystore.p12");
        EbmsSecurityProperties props = new EbmsSecurityProperties();
        props.setKeystorePath(ks.getPath());
        props.setKeystorePassword("testpassword");
        props.setKeystoreAlias("ebms-test");

        assertThatNoException().isThrownBy(() ->
                new HttpMessageTransport(RestClient.builder(), props));
    }

    @Test
    void constructor_withoutKeystore_buildsPlainClient() {
        EbmsSecurityProperties props = new EbmsSecurityProperties();

        assertThatNoException().isThrownBy(() ->
                new HttpMessageTransport(RestClient.builder(), props));
    }
}
