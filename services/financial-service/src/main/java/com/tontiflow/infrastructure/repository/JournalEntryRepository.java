package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    /** Support de l'idempotence (décision R2, §12) : permet de retrouver une écriture déjà comptabilisée après une violation de contrainte UNIQUE sur {@code idempotency_key}. */
    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);
}
