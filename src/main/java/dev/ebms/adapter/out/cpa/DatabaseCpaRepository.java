package dev.ebms.adapter.out.cpa;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.exception.InvalidCpaException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code cpas} table is the store of record; the map is the read path.
 *
 * <p>Two things about the map are load-bearing. It is refreshed <em>per entry</em> on a write,
 * because cpa-service's console sync posts every active CPA one call at a time and rebuilding
 * the whole projection per call would re-parse the entire set on every one of them. And a
 * rebuild swaps a new map in rather than clearing the live one: an operator triggers a rebuild
 * from the console with {@code DELETE /cpas/cache} while traffic is flowing, and clearing in
 * place would make unrelated CPAs briefly unresolvable.
 *
 * <p>The map means a write on one pod is not seen by another until a cache clear. That is
 * acceptable only because {@code replicaCount} is pinned at 1 -- which the chart already does
 * for an unrelated reason (two retry polls double-send). If replicas ever go above 1, this has
 * to become read-through or TTL-bounded.
 */
@Repository
public class DatabaseCpaRepository implements CpaRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCpaRepository.class);

    private final CpaJpaRepository cpas;
    private final CpaDocumentParser parser;

    private volatile Map<String, Cpa> projection = new ConcurrentHashMap<>();

    public DatabaseCpaRepository(CpaJpaRepository cpas, CpaDocumentParser parser,
                                 MeterRegistry meters) {
        this.cpas = cpas;
        this.parser = parser;
        // An empty store is the cutover failure mode: nothing migrates ebms-core's CPAs across,
        // so until cpa-service syncs, every submit answers 400 CpaNotFound and every inbound
        // message is unroutable. Make that a number an operator can see rather than something
        // inferred from failing messages.
        Gauge.builder("ebms.cpas.resolvable", projectionSize())
                .description("CPAs the message paths can resolve")
                .register(meters);
    }

    private java.util.function.Supplier<Number> projectionSize() {
        return () -> projection.size();
    }

    /**
     * Loaded on ApplicationReadyEvent rather than {@code @PostConstruct}: this reads a table, so
     * it has to run after Flyway and the EntityManagerFactory rather than against whatever order
     * bean initialisation happens to produce.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void loadOnStartup() {
        reload();
    }

    @Override
    public Optional<Cpa> findByCpaId(String cpaId) {
        return Optional.ofNullable(projection.get(cpaId));
    }

    @Override
    public List<Cpa> findAll() {
        return List.copyOf(projection.values());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findAllCpaIds() {
        return cpas.findAllCpaIds();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findDocument(String cpaId) {
        return cpas.findById(cpaId).map(CpaEntity::getDocument);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCpaId(String cpaId) {
        return cpas.existsById(cpaId);
    }

    @Override
    @Transactional
    public void save(String cpaId, String document, String contentType, String uploadedBy) {
        // Project first: a document we cannot route must not reach the table, and the caller
        // gets the parser's own message rather than a constraint violation later.
        Cpa projected = projectOrThrow(cpaId, document, contentType);

        CpaEntity entity = cpas.findById(cpaId)
                .orElseGet(() -> new CpaEntity(cpaId, document, contentType, Instant.now(), uploadedBy));
        entity.setDocument(document);
        entity.setContentType(contentType);
        entity.setUpdatedAt(Instant.now());
        entity.setUploadedBy(uploadedBy);
        cpas.save(entity);

        projection.put(cpaId, projected);
        log.info("Stored CPA {} ({})", cpaId, contentType);
    }

    @Override
    @Transactional
    public boolean deleteByCpaId(String cpaId) {
        boolean existed = cpas.existsById(cpaId);
        if (existed) {
            cpas.deleteById(cpaId);
        }
        projection.remove(cpaId);
        return existed;
    }

    @Override
    @Transactional(readOnly = true)
    public void reload() {
        Map<String, Cpa> rebuilt = new ConcurrentHashMap<>();
        int skipped = 0;
        for (CpaEntity entity : cpas.findAll()) {
            Optional<Cpa> projected =
                    project(entity.getCpaId(), entity.getDocument(), entity.getContentType());
            if (projected.isPresent()) {
                rebuilt.put(entity.getCpaId(), projected.get());
            } else {
                skipped++;
            }
        }
        int before = projection.size();
        projection = rebuilt;
        log.info("CPA projection reloaded: {} -> {} CPA(s){}", before, rebuilt.size(),
                skipped > 0 ? " (" + skipped + " unprojectable, skipped)" : "");
        if (rebuilt.isEmpty()) {
            log.warn("No CPAs are resolvable. Nothing can be sent or received until the store is "
                    + "populated -- at cutover that is cpa-service's PUT /cpa/sync.");
        }
    }

    /** On the write path the reason is the answer, so it is carried out to the caller. */
    private Cpa projectOrThrow(String cpaId, String document, String contentType) {
        try {
            return parser.project(document, contentType, cpaId);
        } catch (Exception e) {
            throw new InvalidCpaException("CPA " + cpaId + " was not stored: " + e.getMessage(), e);
        }
    }

    /**
     * On the read path a row that cannot be projected is logged and skipped rather than taking
     * the whole projection down with it. The table only accepts documents that projected when
     * they were stored, but a document stored before a parser change is a real case.
     */
    private Optional<Cpa> project(String cpaId, String document, String contentType) {
        try {
            return Optional.of(parser.project(document, contentType, cpaId));
        } catch (Exception e) {
            log.error("CPA {} cannot be projected: {}", cpaId, e.getMessage());
            return Optional.empty();
        }
    }
}
