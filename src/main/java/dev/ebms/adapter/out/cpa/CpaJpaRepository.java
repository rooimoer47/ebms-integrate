package dev.ebms.adapter.out.cpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

interface CpaJpaRepository extends JpaRepository<CpaEntity, String> {

    /**
     * The ids as the table holds them, sorted. Deliberately not taken from the projection cache:
     * a document the cache skipped would otherwise be invisible to cpa-service's sync, which
     * deletes from us exactly what we list and it does not hold as active -- so an unprojectable
     * CPA could never be cleaned up.
     */
    @Query("select c.cpaId from CpaEntity c order by c.cpaId")
    List<String> findAllCpaIds();
}
