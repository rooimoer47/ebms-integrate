package dev.ebms.application.port.out;

import dev.ebms.domain.Cpa;

import java.util.List;
import java.util.Optional;

public interface CpaRepository {

    Optional<Cpa> findByCpaId(String cpaId);

    List<Cpa> findAll();

    void reload();
}
