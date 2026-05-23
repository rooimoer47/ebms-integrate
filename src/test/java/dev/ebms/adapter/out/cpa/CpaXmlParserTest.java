package dev.ebms.adapter.out.cpa;

import dev.ebms.domain.Cpa;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpaXmlParserTest {

    private static final Path TEST_CPA;

    static {
        try {
            TEST_CPA = Path.of(CpaXmlParserTest.class.getResource("/test-cpa.xml").toURI());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final CpaXmlParser parser = new CpaXmlParser();

    @Test
    void parse_extractsCpaId() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "our-company");
        assertThat(cpa.cpaId()).isEqualTo("cpa-partner-a");
    }

    @Test
    void parse_withOurPartyId_setsFromAndToPartyCorrectly() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "our-company");
        assertThat(cpa.fromParty().partyId()).isEqualTo("our-company");
        assertThat(cpa.toParty().partyId()).isEqualTo("partner-a");
    }

    @Test
    void parse_withOurPartyIdAsSecondParty_stillIdentifiesCorrectly() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "partner-a");
        assertThat(cpa.fromParty().partyId()).isEqualTo("partner-a");
        assertThat(cpa.toParty().partyId()).isEqualTo("our-company");
    }

    @Test
    void parse_withoutOurPartyId_defaultsToFirstPartyAsFrom() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "");
        assertThat(cpa.fromParty().partyId()).isEqualTo("our-company");
        assertThat(cpa.toParty().partyId()).isEqualTo("partner-a");
    }

    @Test
    void parse_extractsPartyIdType() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "our-company");
        assertThat(cpa.fromParty().partyIdType()).isEqualTo("urn:example:partyIdType");
        assertThat(cpa.toParty().partyIdType()).isEqualTo("urn:example:partyIdType");
    }

    @Test
    void parse_extractsPartnerTransportUrl() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "our-company");
        assertThat(cpa.transportUrl()).isEqualTo("https://partner-a.example.com/ebms/msh");
    }

    @Test
    void parse_reliableMessagingPresent_setsAckRequested() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "our-company");
        assertThat(cpa.ackRequested()).isTrue();
    }

    @Test
    void parse_duplicateEliminationPresent_setsDuplicateElimination() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "our-company");
        assertThat(cpa.duplicateElimination()).isTrue();
    }

    @Test
    void parse_extractsRetries() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "our-company");
        assertThat(cpa.retries()).isEqualTo(3);
    }

    @Test
    void parse_extractsRetryInterval() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, "our-company");
        assertThat(cpa.retryInterval()).isEqualTo(Duration.ofSeconds(60));
    }
}
