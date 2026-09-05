package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.enums.FinancialAccountType;
import com.tontiflow.domain.model.FinancialAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, UUID> {

    Optional<FinancialAccount> findByOwnerReferenceAndAccountType(Long ownerReference, FinancialAccountType accountType);

    /** Verrou pessimiste — réutilisé pour la revalidation lors de la création concurrente d'un compte (même patron que {@code TontineRoundRepository.findByIdForUpdate}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM FinancialAccount a WHERE a.id = :id")
    Optional<FinancialAccount> findByIdForUpdate(@Param("id") UUID id);
}
