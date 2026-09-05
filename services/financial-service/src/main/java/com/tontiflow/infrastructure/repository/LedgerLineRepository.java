package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.LedgerLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerLineRepository extends JpaRepository<LedgerLine, UUID> {

    List<LedgerLine> findByFinancialAccount_Id(UUID financialAccountId);

    /**
     * Solde dérivé du Ledger (décision R2, §10) : {@code Σdebit - Σcredit},
     * convention unique et uniforme pour tous les types de compte — jamais
     * un champ {@code balance} mis en cache séparément.
     */
    @Query("SELECT COALESCE(SUM(l.debit), 0) - COALESCE(SUM(l.credit), 0) "
            + "FROM LedgerLine l WHERE l.financialAccount.id = :accountId")
    BigDecimal computeBalance(@Param("accountId") UUID accountId);
}
