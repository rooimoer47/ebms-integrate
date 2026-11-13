package dev.ebms.adapter.out.cpa;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.exception.InvalidCpaException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The table is the store of record and the map is the read path -- including when they differ. */
@SpringBootTest
class DatabaseCpaRepositoryTest {

    static final EmbeddedPostgres POSTGRES;
    static final Path CPA_DIR;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
            CPA_DIR = Files.createTempDirectory("ebms-cpa-store");
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

    @Autowired
    CpaRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private String document;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM cpas");
        repository.reload();
        document = Files.readString(
                Path.of(getClass().getResource("/test-cpa.xml").toURI()));
    }

    @Test
    void save_storesTheDocumentVerbatim() {
        repository.save("cpa-partner-a", document, CpaDocumentParser.XML, "test");

        assertThat(repository.findDocument("cpa-partner-a")).contains(document);
    }

    @Test
    void save_makesTheCpaResolvableWithoutAReload() {
        repository.save("cpa-partner-a", document, CpaDocumentParser.XML, "test");

        Optional<Cpa> found = repository.findByCpaId("cpa-partner-a");
        assertThat(found).isPresent();
        assertThat(found.get().toParty().partyId()).isEqualTo("partner-a");
    }

    @Test
    void save_twice_replacesTheRowAndKeepsOneEntry() {
        repository.save("cpa-partner-a", document, CpaDocumentParser.XML, "first");
        repository.save("cpa-partner-a", document, CpaDocumentParser.XML, "second");

        assertThat(repository.findAllCpaIds()).containsExactly("cpa-partner-a");
        assertThat(jdbc.queryForObject(
                "SELECT uploaded_by FROM cpas WHERE cpa_id = 'cpa-partner-a'", String.class))
                .isEqualTo("second");
    }

    /** A document that cannot be routed must not reach the table. */
    @Test
    void save_unprojectableDocument_storesNothing() {
        assertThatThrownBy(() ->
                repository.save("broken", "<not-a-cpa/>", CpaDocumentParser.XML, "test"))
                .isInstanceOf(InvalidCpaException.class)
                .as("the parser's own reason reaches the caller, who shows it to an operator")
                .hasMessageContaining("Missing cpaid");

        assertThat(repository.findAllCpaIds()).isEmpty();
        assertThat(repository.findByCpaId("broken")).isEmpty();
    }

    @Test
    void delete_removesFromBothTheTableAndTheProjection() {
        repository.save("cpa-partner-a", document, CpaDocumentParser.XML, "test");

        assertThat(repository.deleteByCpaId("cpa-partner-a")).isTrue();
        assertThat(repository.findByCpaId("cpa-partner-a")).isEmpty();
        assertThat(repository.findAllCpaIds()).isEmpty();
    }

    @Test
    void delete_unknownCpaId_reportsThatNothingExisted() {
        assertThat(repository.deleteByCpaId("never-stored")).isFalse();
    }

    /** The cache clear is the only path by which an out-of-band change becomes visible. */
    @Test
    void reload_picksUpARowWrittenOutsideTheApplication() {
        jdbc.update("INSERT INTO cpas (cpa_id, document, content_type, updated_at) "
                + "VALUES (?, ?, ?, now())", "cpa-partner-a", document, CpaDocumentParser.XML);

        assertThat(repository.findByCpaId("cpa-partner-a"))
                .as("not visible before the reload")
                .isEmpty();

        repository.reload();

        assertThat(repository.findByCpaId("cpa-partner-a")).isPresent();
    }

    /**
     * A row stored before a parser change is a real case, and it must not take the rest of the
     * projection down with it.
     */
    @Test
    void reload_unprojectableRow_isSkippedAndTheOthersSurvive() {
        repository.save("cpa-partner-a", document, CpaDocumentParser.XML, "test");
        jdbc.update("INSERT INTO cpas (cpa_id, document, content_type, updated_at) "
                + "VALUES (?, ?, ?, now())", "cpa-broken", "<not-a-cpa/>", CpaDocumentParser.XML);

        repository.reload();

        assertThat(repository.findByCpaId("cpa-partner-a")).isPresent();
        assertThat(repository.findByCpaId("cpa-broken")).isEmpty();
        assertThat(repository.findAllCpaIds())
                .as("the table lists it even though the projection skipped it, "
                        + "so cpa-service's sync can still clean it up")
                .containsExactly("cpa-broken", "cpa-partner-a");
    }

    @Test
    void findDocument_unknownCpaId_isEmpty() {
        assertThat(repository.findDocument("never-stored")).isEmpty();
    }

    @Test
    void existsByCpaId_reflectsTheTable() {
        assertThat(repository.existsByCpaId("cpa-partner-a")).isFalse();
        repository.save("cpa-partner-a", document, CpaDocumentParser.XML, "test");
        assertThat(repository.existsByCpaId("cpa-partner-a")).isTrue();
    }
}
