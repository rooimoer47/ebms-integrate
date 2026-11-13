package dev.ebms.adapter.out.cpa;

import dev.ebms.domain.exception.InvalidCpaException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Held against real agreements rather than hand-written ones: the fixtures here are CPAs from
 * cpa-service's own test data, which is the only honest test of how strict this should be.
 */
class CpaValidatorTest {

    private static String realCpa;
    private static String intermediaryCpa;
    private static String handWritten;

    /**
     * Both real fixtures have already expired -- one in June 2025, one in January 2026 -- which
     * is itself the finding behind P2-13. The happy-path tests move the end date forward.
     */
    private static String unexpired(String cpa) {
        return cpa.replaceAll("<ns1:End>[^<]*</ns1:End>", "<ns1:End>2099-01-01T00:00:00Z</ns1:End>");
    }

    @BeforeAll
    static void load() throws Exception {
        realCpa = read("/real-cpas/valid_cpa.xml");
        intermediaryCpa = read("/real-cpas/valid_intermediaryCPA.xml");
        handWritten = read("/test-cpa.xml");
    }

    private static String read(String resource) throws Exception {
        return Files.readString(Path.of(CpaValidatorTest.class.getResource(resource).toURI()));
    }

    /** Both real fixtures name this OIN, which is one of the two in cpa.default.server.oin. */
    private final CpaDocumentParser parser =
            new CpaDocumentParser("config/cpas", List.of("00000004003214345001"));

    private final CpaValidator validator =
            new CpaValidator(new CpaXsdValidator(), parser, CpaValidator.EndpointPolicy.WARN);

    private final CpaValidator rejectingCleartext =
            new CpaValidator(new CpaXsdValidator(), parser, CpaValidator.EndpointPolicy.REJECT);

    private void validate(String document) {
        validator.validate(document, CpaDocumentParser.XML, "fixture", CpaValidator.Strictness.FULL);
    }

    @Test
    void realCpa_passesTheSchemaAndProjects() {
        assertThatCode(() -> validate(unexpired(realCpa))).doesNotThrowAnyException();
    }

    /**
     * The one that matters most: a CPA with a 48-digit X509SerialNumber. libxml2 rejects it as
     * not an xs:integer; Xerces, which is what actually runs, does not. If this ever fails, our
     * validator has become stricter than the one it replaces.
     */
    @Test
    void realIntermediaryCpa_withLargeCertificateSerial_isAccepted() {
        assertThatCode(() -> validate(unexpired(intermediaryCpa))).doesNotThrowAnyException();
    }

    @Test
    void expiredCpa_isRejected() {
        assertThatThrownBy(() -> validate(realCpa))
                .isInstanceOf(InvalidCpaException.class)
                .hasMessageContaining("expired on");
    }

    @Test
    void proposedCpa_isRejected() {
        String proposed = unexpired(realCpa).replace("ns1:value=\"agreed\"", "ns1:value=\"proposed\"");

        assertThatThrownBy(() -> validate(proposed))
                .isInstanceOf(InvalidCpaException.class)
                .hasMessageContaining("status proposed");
    }

    @Test
    void startNotBeforeEnd_isRejected() {
        String inverted = unexpired(realCpa)
                .replace("<ns1:Start>2018-06-27T00:00:00Z</ns1:Start>",
                        "<ns1:Start>2100-06-27T00:00:00Z</ns1:Start>");

        assertThatThrownBy(() -> validate(inverted))
                .isInstanceOf(InvalidCpaException.class)
                .hasMessageContaining("not before its end");
    }

    @Test
    void documentThatIsNotACpa_isRejectedBySchema() {
        assertThatThrownBy(() -> validate("<not-a-cpa/>"))
                .isInstanceOf(InvalidCpaException.class)
                .hasMessageContaining("CPPA 2.0 schema");
    }

    /**
     * Real CPAs in this platform carry http:// endpoints, so rejecting cleartext would refuse
     * agreements that work today. WARN is the default for that reason.
     */
    @Test
    void cleartextEndpoint_isWarnedAboutByDefault() {
        assertThat(unexpired(realCpa)).contains("href=\"http://");

        assertThatCode(() -> validate(unexpired(realCpa))).doesNotThrowAnyException();
    }

    @Test
    void cleartextEndpoint_isRejectedWhenThePolicySaysSo() {
        assertThatThrownBy(() -> rejectingCleartext.validate(
                unexpired(realCpa), CpaDocumentParser.XML, "fixture", CpaValidator.Strictness.FULL))
                .isInstanceOf(InvalidCpaException.class)
                .hasMessageContaining("cleartext endpoint");
    }

    /**
     * The bootstrap path takes operator-supplied files from a mounted volume, so it checks that
     * a document can be routed without holding it to the schema the API enforces.
     */
    @Test
    void structuralStrictness_skipsTheSchema() {
        CpaValidator forFiles = new CpaValidator(new CpaXsdValidator(),
                new CpaDocumentParser("config/cpas", List.of("our-company")),
                CpaValidator.EndpointPolicy.WARN);

        assertThatCode(() -> forFiles.validate(handWritten, CpaDocumentParser.XML, "test-cpa.xml",
                CpaValidator.Strictness.STRUCTURAL)).doesNotThrowAnyException();

        assertThatThrownBy(() -> forFiles.validate(handWritten, CpaDocumentParser.XML,
                "test-cpa.xml", CpaValidator.Strictness.FULL))
                .isInstanceOf(InvalidCpaException.class);
    }
}
