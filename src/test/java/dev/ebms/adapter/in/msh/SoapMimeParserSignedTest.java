package dev.ebms.adapter.in.msh;

import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Party;
import dev.ebms.domain.exception.MessageParseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoapMimeParserSignedTest {

    static SoapMimeParser parser;
    static SoapMimeSerializer serializer;

    @BeforeAll
    static void setup() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = SoapMimeParserSignedTest.class.getResourceAsStream("/test-keystore.p12")) {
            ks.load(is, "testpassword".toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("ebms-test", "testpassword".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("ebms-test");
        XmlSignatureService signatureService = new XmlSignatureService(key, cert);
        parser = new SoapMimeParser(signatureService);
        serializer = new SoapMimeSerializer(signatureService);
    }

    @Test
    void parse_signedMessage_succeeds() {
        EbmsMessage outbound = EbmsMessage.newOutbound(
                "msg-001@test", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder", List.of(), false);

        byte[] signed = serializer.serialize(outbound).body();
        EbmsMessage parsed = parser.parse(signed, "text/xml");

        assertThat(parsed.messageId()).isEqualTo("msg-001@test");
        assertThat(parsed.action()).isEqualTo("NewOrder");
    }

    @Test
    void parse_tamperedSignedMessage_throwsSecurityFailure() {
        EbmsMessage outbound = EbmsMessage.newOutbound(
                "msg-002@test", "conv-001", "cpa-001",
                Party.of("our-company"), Party.of("partner-a"),
                "OrderService", "NewOrder", List.of(), false);

        byte[] signed = serializer.serialize(outbound).body();
        String body = new String(signed, StandardCharsets.UTF_8)
                .replace("NewOrder", "TamperedAction");

        assertThatThrownBy(() -> parser.parse(body.getBytes(StandardCharsets.UTF_8), "text/xml"))
                .isInstanceOf(MessageParseException.class)
                .satisfies(e -> assertThat(((MessageParseException) e).getEbmsErrorCode())
                        .isEqualTo("SecurityFailure"));
    }
}
