package dev.ebms.adapter.out.cpa;

import dev.ebms.domain.Cpa;
import dev.ebms.domain.exception.InvalidCpaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every real CPA we can lay hands on, through the whole write path.
 *
 * <p>This is the half of P2-12 that actually de-risks the phase. Our own tests assert the
 * behaviour we designed; only real agreements say whether the validator is stricter than the
 * one it replaces, and a validator that is stricter rejects partners at cutover rather than
 * failing in a build.
 *
 * <p>The corpus is cpa-service's own test data. It is a stand-in for the production export,
 * which still wants running before this phase ships -- but it already carries the shapes that
 * matter: OIN party ids, {@code $} in the cpaId, large certificate serials, http endpoints and
 * an intermediary.
 */
class RealCpaCorpusTest {

    private static final Path CORPUS = Path.of("src/test/resources/real-cpas");

    /** Both OINs cpa-service is configured with, as {@code cpa.default.server.oin} carries them. */
    private static final List<String> OUR_PARTY_IDS =
            List.of("00000004003214345001", "00000004194049711000");

    private final CpaValidator validator = new CpaValidator(
            new CpaXsdValidator(),
            new CpaDocumentParser("config/cpas", OUR_PARTY_IDS),
            CpaValidator.EndpointPolicy.WARN);

    static Stream<Path> corpus() throws Exception {
        try (Stream<Path> files = Files.list(CORPUS)) {
            return files.filter(p -> p.toString().endsWith(".xml")).sorted().toList().stream();
        }
    }

    private static String unexpired(String cpa) {
        return cpa.replaceAll("<ns1:End>[^<]*</ns1:End>", "<ns1:End>2099-01-01T00:00:00Z</ns1:End>");
    }

    /**
     * Documents in the corpus the CPPA 2.0 schema refuses, with the reason. ebms-core validates
     * against the same schema with the same parser, so it refuses them too -- these are not
     * places where we are stricter than what we replace. Both Xerces and libxml2 agree.
     */
    private static final Map<String, String> KNOWN_SCHEMA_FAILURES = Map.of(
            "valid_cpa_new_role.xml",
            "duplicate xs:ID: two ThisPartyActionBinding elements share one id");

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void everyRealCpa_validatesAndProjects(Path file) throws Exception {
        String document = unexpired(Files.readString(file));
        String name = String.valueOf(file.getFileName());

        if (KNOWN_SCHEMA_FAILURES.containsKey(name)) {
            assertThatThrownBy(() -> validator.validate(document, CpaDocumentParser.XML, name,
                    CpaValidator.Strictness.FULL))
                    .as("%s: %s", name, KNOWN_SCHEMA_FAILURES.get(name))
                    .isInstanceOf(InvalidCpaException.class)
                    .hasMessageContaining("CPPA 2.0 schema");
            return;
        }

        assertThatCode(() -> {
            Cpa cpa = validator.validate(document, CpaDocumentParser.XML, name,
                    CpaValidator.Strictness.FULL);
            assertThat(cpa.cpaId()).isNotBlank();
            assertThat(cpa.transportUrl())
                    .as("no transport URL means nowhere to send")
                    .isNotBlank();
            assertThat(cpa.toParty().partyId()).isNotBlank();
        }).doesNotThrowAnyException();
    }

    /**
     * One transport endpoint per CPA is an accepted simplification. A CPA with per-service
     * delivery channels would route to the wrong partner endpoint silently rather than failing,
     * so this asserts the assumption over real documents instead of trusting it.
     *
     * <p>ebms-core takes the same position in code: it warns about multiple endpoints and uses
     * the first.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void everyRealCpa_hasOneReceiverEndpointPerParty(Path file) throws Exception {
        String document = Files.readString(file);

        long endpoints = document.split("<ns1:TransportReceiver>", -1).length - 1;
        long perPartyEndpoints = document.split("<ns1:Endpoint", -1).length - 1;

        assertThat(perPartyEndpoints)
                .as("%s declares %d TransportReceiver blocks; more endpoints than that means "
                        + "per-service delivery channels", file.getFileName(), endpoints)
                .isLessThanOrEqualTo(endpoints);
    }

    /**
     * Both of these expired before today. They are refused here exactly as ebms-core refuses
     * them, which is the finding behind the cutover runbook: a CPA still marked active in
     * cpa-service but past its end date will be rejected by the sync, and the count wants to be
     * known before the switch rather than discovered during it.
     */
    @Test
    void expiredCpasInTheCorpus_areRejected() throws Exception {
        List<Path> expired = corpus().filter(file -> {
            try {
                validator.validate(Files.readString(file), CpaDocumentParser.XML,
                        String.valueOf(file.getFileName()), CpaValidator.Strictness.FULL);
                return false;
            } catch (InvalidCpaException e) {
                return e.getMessage().contains("expired on");
            } catch (Exception e) {
                return false;
            }
        }).toList();

        assertThat(expired)
                .as("if this ever empties, the corpus was refreshed and the runbook note "
                        + "about expired-but-active CPAs should be re-checked")
                .isNotEmpty();
    }
}
