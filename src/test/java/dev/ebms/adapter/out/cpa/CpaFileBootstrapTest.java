package dev.ebms.adapter.out.cpa;

import dev.ebms.application.port.out.CpaRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Files seed an empty store. The rule that matters is the one about what they must never do. */
@SpringBootTest
class CpaFileBootstrapTest {

    static final EmbeddedPostgres POSTGRES;
    static final Path CPA_DIR;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            CPA_DIR = Files.createTempDirectory("ebms-cpa-bootstrap");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("ebms.cpa-directory", CPA_DIR::toString);
        registry.add("ebms.host-party-ids", () -> "our-company");
        registry.add("ebms.security.api.username", () -> "ebms");
        registry.add("ebms.security.api.password", () -> "test-secret");
    }

    private static final String CPA_YAML = """
            cpaId: "cpa-bootstrap"
            fromParty:
              partyId: "our-company"
            toParty:
              partyId: "partner-a"
            transportUrl: "https://partner-a.example.com/ebms/msh"
            ackRequested: true
            duplicateElimination: true
            retries: 3
            retryIntervalSeconds: 60
            """;

    @Autowired
    CpaFileBootstrap bootstrap;

    @Autowired
    CpaRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() throws IOException {
        jdbc.update("DELETE FROM cpas");
        repository.reload();
        try (var files = Files.list(CPA_DIR)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    @Test
    void emptyStore_isSeededFromFiles() throws IOException {
        Files.writeString(CPA_DIR.resolve("cpa-bootstrap.yml"), CPA_YAML);

        bootstrap.seedIfEmpty();
        repository.reload();

        assertThat(repository.findAllCpaIds()).containsExactly("cpa-bootstrap");
        assertThat(repository.findByCpaId("cpa-bootstrap")).isPresent();
    }

    @Test
    void seededDocument_isStoredVerbatimWithItsFormat() throws IOException {
        Files.writeString(CPA_DIR.resolve("cpa-bootstrap.yml"), CPA_YAML);

        bootstrap.seedIfEmpty();

        assertThat(repository.findDocument("cpa-bootstrap")).contains(CPA_YAML);
        assertThat(jdbc.queryForObject(
                "SELECT content_type FROM cpas WHERE cpa_id = 'cpa-bootstrap'", String.class))
                .isEqualTo(CpaDocumentParser.YAML);
    }

    @Test
    void nonEmptyStore_isLeftAlone() throws IOException {
        Files.writeString(CPA_DIR.resolve("cpa-bootstrap.yml"), CPA_YAML);
        bootstrap.seedIfEmpty();

        Files.writeString(CPA_DIR.resolve("cpa-second.yml"),
                CPA_YAML.replace("cpa-bootstrap", "cpa-second"));
        bootstrap.seedIfEmpty();

        assertThat(repository.findAllCpaIds())
                .as("files seed an empty store; they are not a source of truth")
                .containsExactly("cpa-bootstrap");
    }

    /**
     * The trap this design exists to avoid: a CPA deleted through the API coming back on the
     * next restart, resurrecting a partner whose agreement was revoked.
     */
    @Test
    void deletedCpa_isNotResurrectedWhileOtherCpasRemain() throws IOException {
        Files.writeString(CPA_DIR.resolve("a.yml"), CPA_YAML);
        Files.writeString(CPA_DIR.resolve("b.yml"), CPA_YAML.replace("cpa-bootstrap", "cpa-second"));
        bootstrap.seedIfEmpty();

        repository.deleteByCpaId("cpa-bootstrap");
        bootstrap.seedIfEmpty();

        assertThat(repository.findAllCpaIds()).containsExactly("cpa-second");
    }

    /**
     * The one gap in seed-on-empty, asserted so it is a known shape rather than a surprise:
     * delete the last CPA and the store is empty again, so the bootstrap runs. It cannot bite
     * where it would matter -- an environment with cpa-service sets `cpa.files` empty, so there
     * is nothing to seed from -- and where there is no cpa-service, re-seeding is the point.
     */
    @Test
    void emptyingTheStoreEntirely_reEnablesTheBootstrap() throws IOException {
        Files.writeString(CPA_DIR.resolve("a.yml"), CPA_YAML);
        bootstrap.seedIfEmpty();

        repository.deleteByCpaId("cpa-bootstrap");
        bootstrap.seedIfEmpty();

        assertThat(repository.findAllCpaIds()).containsExactly("cpa-bootstrap");
    }

    @Test
    void malformedFile_isSkippedAndTheOthersAreSeeded() throws IOException {
        Files.writeString(CPA_DIR.resolve("a-broken.xml"), "<not-a-cpa/>");
        Files.writeString(CPA_DIR.resolve("b-good.yml"), CPA_YAML);

        bootstrap.seedIfEmpty();

        assertThat(repository.findAllCpaIds()).containsExactly("cpa-bootstrap");
    }
}
