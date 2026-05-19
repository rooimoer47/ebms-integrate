package dev.ebms;

import dev.ebms.adapter.in.msh.SoapMimeParser;
import dev.ebms.domain.EbmsMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EbmsApplicationTest {

    private final SoapMimeParser parser = new SoapMimeParser();

    private static final String SOAP_MESSAGE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:eb="http://www.oasis-open.org/committees/ebxml-msg/schema/msg-header-2_0.xsd">
              <SOAP-ENV:Header>
                <eb:MessageHeader SOAP-ENV:mustUnderstand="1" eb:version="2.0">
                  <eb:From><eb:PartyId>partner-a</eb:PartyId></eb:From>
                  <eb:To><eb:PartyId>our-company</eb:PartyId></eb:To>
                  <eb:CPAId>cpa-partner-a</eb:CPAId>
                  <eb:ConversationId>conv-001</eb:ConversationId>
                  <eb:Service>OrderService</eb:Service>
                  <eb:Action>NewOrder</eb:Action>
                  <eb:MessageData>
                    <eb:MessageId>msg-001@partner-a.example.com</eb:MessageId>
                    <eb:Timestamp>2025-05-18T12:00:00Z</eb:Timestamp>
                  </eb:MessageData>
                </eb:MessageHeader>
                <eb:AckRequested SOAP-ENV:mustUnderstand="1" eb:version="2.0" eb:signed="false"/>
              </SOAP-ENV:Header>
              <SOAP-ENV:Body/>
            </SOAP-ENV:Envelope>
            """;

    @Test
    void parsesRequiredHeaderFields() {
        EbmsMessage msg = parser.parse(SOAP_MESSAGE.getBytes(), "text/xml");

        assertThat(msg.messageId()).isEqualTo("msg-001@partner-a.example.com");
        assertThat(msg.conversationId()).isEqualTo("conv-001");
        assertThat(msg.cpaId()).isEqualTo("cpa-partner-a");
        assertThat(msg.service()).isEqualTo("OrderService");
        assertThat(msg.action()).isEqualTo("NewOrder");
        assertThat(msg.from().partyId()).isEqualTo("partner-a");
        assertThat(msg.to().partyId()).isEqualTo("our-company");
        assertThat(msg.timestamp()).isEqualTo(Instant.parse("2025-05-18T12:00:00Z"));
        assertThat(msg.direction()).isEqualTo(EbmsMessage.Direction.INBOUND);
        assertThat(msg.ackRequested()).isTrue();
        assertThat(msg.payloads()).isEmpty();
    }

    @Test
    void throwsOnMissingMessageId() {
        String broken = SOAP_MESSAGE.replace(
                "<eb:MessageId>msg-001@partner-a.example.com</eb:MessageId>", "");

        assertThatThrownBy(() -> parser.parse(broken.getBytes(), "text/xml"))
                .hasMessageContaining("MessageId");
    }
}
