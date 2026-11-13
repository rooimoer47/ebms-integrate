package dev.ebms.adapter.out.cpa;

import dev.ebms.application.port.out.CpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Seeds the CPA store from mounted files, so docker-compose and any environment without
 * cpa-service still come up with known CPAs.
 *
 * <p>It seeds <em>only an empty table</em>, and never updates or re-creates an existing row.
 * That restriction is the whole design: if seeding upserted, deleting a CPA through the API
 * would be silently undone by the next pod restart, and a partner whose agreement was revoked
 * would come back.
 *
 * <p>One gap follows from "empty" being the condition: delete the last CPA and the store is
 * empty again, so the next startup seeds. That cannot bite where it would matter, because an
 * environment with cpa-service leaves {@code cpa.files} empty and has nothing to seed from --
 * and where there is no cpa-service, re-seeding is the intent.
 */
@Component
public class CpaFileBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CpaFileBootstrap.class);

    private final CpaRepository cpas;
    private final CpaDocumentParser parser;
    private final Path directory;
    private final boolean enabled;

    public CpaFileBootstrap(CpaRepository cpas,
                            CpaDocumentParser parser,
                            @Value("${ebms.cpa-directory}") String directory,
                            @Value("${ebms.cpa-bootstrap-enabled:true}") boolean enabled) {
        this.cpas = cpas;
        this.parser = parser;
        this.directory = Path.of(directory);
        this.enabled = enabled;
    }

    /** Before the projection is built, so a seeded CPA is resolvable without a second pass. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void seedIfEmpty() {
        if (!enabled) {
            log.debug("CPA file bootstrap disabled");
            return;
        }
        if (!Files.exists(directory)) {
            log.warn("CPA bootstrap directory does not exist: {}", directory);
            return;
        }
        List<String> existing = cpas.findAllCpaIds();
        if (!existing.isEmpty()) {
            log.info("CPA store already holds {} CPA(s); skipping the file bootstrap. "
                            + "Files seed an empty store, they are not a source of truth.",
                    existing.size());
            return;
        }

        int seeded = 0;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(CpaFileBootstrap::isCpaFile).sorted().toList()) {
                seeded += seed(file) ? 1 : 0;
            }
        } catch (IOException e) {
            log.error("Failed to read CPA bootstrap directory {}", directory, e);
            return;
        }
        log.info("Bootstrapped {} CPA(s) from {}", seeded, directory);
    }

    private static boolean isCpaFile(Path path) {
        String name = path.toString();
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".xml");
    }

    /** A malformed file names itself and stops neither startup nor the other CPAs. */
    private boolean seed(Path file) {
        try {
            String document = Files.readString(file);
            String contentType = CpaDocumentParser.contentTypeOf(file);
            String source = String.valueOf(file.getFileName());
            // The id comes from the document, as it does for an upload.
            String cpaId = parser.project(document, contentType, source).cpaId();
            cpas.save(cpaId, document, contentType, "bootstrap:" + source);
            return true;
        } catch (Exception e) {
            log.error("Skipping CPA bootstrap file {}: {}", file.getFileName(), e.getMessage());
            return false;
        }
    }
}
