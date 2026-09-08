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

    /**
     * Décision R14-C-B : {@code JOIN FETCH} sur {@code journalEntry} pour
     * éviter le N+1 déjà démontré par {@link
     * com.tontiflow.application.service.LedgerService#getAccountStatement}
     * (accès à {@code LedgerLine.getJournalEntry()} pour chaque ligne du
     * relevé). Sans effet sur le nombre ou l'ordre des résultats — {@code
     * journalEntry} est une association {@code @ManyToOne}, jamais une
     * collection : une jointure sur une relation vers-un ne multiplie jamais
     * les lignes, contrairement à une jointure sur une collection.
     */
    @Query("SELECT l FROM LedgerLine l JOIN FETCH l.journalEntry WHERE l.financialAccount.id = :financialAccountId")
    List<LedgerLine> findByFinancialAccount_Id(@Param("financialAccountId") UUID financialAccountId);

    /**
     * Solde dérivé du Ledger (décision R2, §10) : {@code Σdebit - Σcredit},
     * convention unique et uniforme pour tous les types de compte — jamais
     * un champ {@code balance} mis en cache séparément.
     */
    @Query("SELECT COALESCE(SUM(l.debit), 0) - COALESCE(SUM(l.credit), 0) "
            + "FROM LedgerLine l WHERE l.financialAccount.id = :accountId")
    BigDecimal computeBalance(@Param("accountId") UUID accountId);
}
