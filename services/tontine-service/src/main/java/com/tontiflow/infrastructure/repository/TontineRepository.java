package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.Tontine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TontineRepository extends JpaRepository<Tontine, Long> {

    /**
     * Tontines dont l'utilisateur donné est le créateur (décision D1).
     */
    List<Tontine> findByCreatorUserId(UUID creatorUserId);
}
