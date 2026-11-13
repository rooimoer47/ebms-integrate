package dev.ebms.application.port.out;

import dev.ebms.domain.Cpa;

import java.util.List;
import java.util.Optional;

public interface CpaRepository {

    /** The projection the message paths resolve against. Must not touch the database. */
    Optional<Cpa> findByCpaId(String cpaId);

    List<Cpa> findAll();

    /** Ids as the store holds them, including any document that failed to project. */
    List<String> findAllCpaIds();

    /** The stored document itself, which is what {@code GET /cpas/{cpaId}} answers with. */
    Optional<String> findDocument(String cpaId);

    boolean existsByCpaId(String cpaId);

    /**
     * Stores a document and refreshes its single projection entry. Replaces any existing row --
     * the caller decides whether replacing was allowed.
     *
     * @throws IllegalArgumentException if the document cannot be projected; nothing is stored
     */
    void save(String cpaId, String document, String contentType, String uploadedBy);

    /** @return whether a row existed. Deleting an unknown cpaId is not an error. */
    boolean deleteByCpaId(String cpaId);

    /** Rebuilds the whole projection from the store. */
    void reload();
}
