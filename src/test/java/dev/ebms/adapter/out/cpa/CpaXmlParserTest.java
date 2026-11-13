package dev.ebms.adapter.out.cpa;

import dev.ebms.domain.Cpa;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpaXmlParserTest {

    private static final Path TEST_CPA;
    private static final Path TEST_CPA_WITH_ROLES;

    static {
        try {
            TEST_CPA = Path.of(CpaXmlParserTest.class.getResource("/test-cpa.xml").toURI());
            TEST_CPA_WITH_ROLES =
                    Path.of(CpaXmlParserTest.class.getResource("/test-cpa-with-roles.xml").toURI());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final CpaXmlParser parser = new CpaXmlParser();

    @Test
    void parse_extractsCpaId() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));
        assertThat(cpa.cpaId()).isEqualTo("cpa-partner-a");
    }

    @Test
    void parse_withOurPartyId_setsFromAndToPartyCorrectly() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));
        assertThat(cpa.fromParty().partyId()).isEqualTo("our-company");
        assertThat(cpa.toParty().partyId()).isEqualTo("partner-a");
    }

    @Test
    void parse_withOurPartyIdAsSecondParty_stillIdentifiesCorrectly() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("partner-a"));
        assertThat(cpa.fromParty().partyId()).isEqualTo("partner-a");
        assertThat(cpa.toParty().partyId()).isEqualTo("our-company");
    }

    /**
     * The fallback this replaces took the first PartyInfo as ours, which meant the transport URL
     * and the partner certificate came from whichever element happened to be second. A CPA
     * listing us second would have posted our own messages back to us.
     */
    @Test
    void parse_whenNoPartyMatchesOurs_isRejectedRatherThanGuessed() {
        assertThatThrownBy(() -> parser.parse(TEST_CPA, List.of("someone-else")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot tell which party is us")
                .hasMessageContaining("our-company")
                .hasMessageContaining("someone-else");
    }

    @Test
    void parse_withNoConfiguredPartyIds_isRejected() {
        assertThatThrownBy(() -> parser.parse(TEST_CPA, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_extractsPartyIdType() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));
        assertThat(cpa.fromParty().partyIdType()).isEqualTo("urn:example:partyIdType");
        assertThat(cpa.toParty().partyIdType()).isEqualTo("urn:example:partyIdType");
    }

    @Test
    void parse_extractsPartnerTransportUrl() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));
        assertThat(cpa.transportUrl()).isEqualTo("https://partner-a.example.com/ebms/msh");
    }

    @Test
    void parse_reliableMessagingPresent_setsAckRequested() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));
        assertThat(cpa.ackRequested()).isTrue();
    }

    @Test
    void parse_duplicateEliminationPresent_setsDuplicateElimination() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));
        assertThat(cpa.duplicateElimination()).isTrue();
    }

    @Test
    void parse_extractsRetries() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));
        assertThat(cpa.retries()).isEqualTo(3);
    }

    @Test
    void parse_extractsRetryInterval() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));
        assertThat(cpa.retryInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void parse_collaborationRoleDeclaresRole_extractsRoleForBothParties() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA_WITH_ROLES, List.of("urn:osb:oin:00000001822477348001"));

        assertThat(cpa.fromParty().role()).isEqualTo("Bronhouder");
        assertThat(cpa.toParty().role()).isEqualTo("Afnemer");
    }

    @Test
    void parse_roleFollowsTheSelectedParty_notDocumentOrder() throws Exception {
        Cpa cpa = parser.parse(TEST_CPA_WITH_ROLES, List.of("urn:osb:oin:00000001810220350000"));

        assertThat(cpa.fromParty().role()).isEqualTo("Afnemer");
        assertThat(cpa.toParty().role()).isEqualTo("Bronhouder");
    }

    @Test
    void parse_noRoleElement_leavesRoleNull() throws Exception {
        // test-cpa.xml has a CollaborationRole but no Role inside it.
        Cpa cpa = parser.parse(TEST_CPA, List.of("our-company"));

        assertThat(cpa.fromParty().role()).isNull();
        assertThat(cpa.toParty().role()).isNull();
    }

    @Test
    void parse_oinPrefixOnEitherSide_stillMatches() throws Exception {
        // CPAs carry the bare OIN; the clients and the chart use the urn:osb:oin: form.
        Cpa bare = parser.parse(TEST_CPA_WITH_ROLES, List.of("00000001822477348001"));
        Cpa prefixed = parser.parse(TEST_CPA_WITH_ROLES, List.of("urn:osb:oin:00000001822477348001"));

        assertThat(bare.fromParty().partyId()).isEqualTo(prefixed.fromParty().partyId());
        assertThat(bare.toParty().partyId()).isEqualTo(prefixed.toParty().partyId());
    }

    @Test
    void parse_anyOneOfSeveralConfiguredIds_matches() throws Exception {
        // This platform has two: cpa.default.server.oin carries both.
        Cpa cpa = parser.parse(TEST_CPA_WITH_ROLES,
                List.of("00000004003214345001", "urn:osb:oin:00000001822477348001"));

        assertThat(cpa.fromParty().role()).isEqualTo("Bronhouder");
    }

    @Test
    void parse_fromBytes_matchesParseFromFile() throws Exception {
        Cpa fromFile = parser.parse(TEST_CPA, List.of("our-company"));
        Cpa fromBytes = parser.parse(Files.readAllBytes(TEST_CPA), "upload", List.of("our-company"));

        assertThat(fromBytes).isEqualTo(fromFile);
    }

    /**
     * The document arrives over the network now, so this is not a theoretical hardening: a
     * DOCTYPE must be refused outright rather than expanded.
     */
    @Test
    void parse_documentWithDoctype_isRefused() {
        byte[] xxe = ("""
                <?xml version="1.0"?>
                <!DOCTYPE cpa [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <CollaborationProtocolAgreement
                    xmlns="%s" cpaid="evil">&xxe;</CollaborationProtocolAgreement>
                """.formatted(CpaXmlParser.CPA_NS)).getBytes();

        assertThatThrownBy(() -> parser.parse(xxe, "upload", List.of("our-company")))
                .isInstanceOf(org.xml.sax.SAXException.class);
    }
}
