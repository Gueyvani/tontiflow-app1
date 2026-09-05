package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountType;
import com.tontiflow.domain.model.FinancialAccount;
import com.tontiflow.domain.model.JournalEntry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cas d'usage métier « versement au bénéficiaire » (décision R6) — moitié
 * symétrique de {@link ContributionService} (décision R3) dans le même
 * cycle financier de la tontine : l'argent qui sort du fonds mutualisé vers
 * le bénéficiaire d'un round, plutôt que l'argent qui y entre.
 *
 * <p>Réutilise {@link LedgerService} comme unique point d'écriture, exactement
 * comme {@link ContributionService} — aucune nouvelle entité, aucun nouveau
 * schéma, {@link FinancialAccountType#TONTINE}/{@link FinancialAccountType#MEMBER}
 * inchangés.</p>
 *
 * <p><b>Convention Débit/Crédit</b> (décision R6) : exact inverse arithmétique
 * de la contribution — <b>Débit = compte {@code MEMBER}</b> (destination, le
 * bénéficiaire reçoit), <b>Crédit = compte {@code TONTINE}</b> (source, le
 * fonds mutualisé diminue). Cohérent avec la convention « Débit = destination »
 * déjà établie en Phase R/R3 ; un cycle complet contribution+versement d'un
 * même montant ramène le compte {@code TONTINE} à un solde net nul, comme
 * attendu d'un compte de passage.</p>
 */
@Service
public class DisbursementService {

    private static final String EVENT_TYPE = "DISBURSEMENT_RECORDED";

    private final LedgerService ledgerService;

    public DisbursementService(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * Enregistre un versement : obtient/crée les deux comptes financiers
     * nécessaires puis comptabilise une écriture équilibrée. Idempotent
     * (délégué à {@link LedgerService#record}) — un second appel avec les
     * mêmes {@code tontineId}/{@code roundId}/{@code beneficiaryId} ne crée
     * jamais une seconde écriture.
     */
    public JournalEntry recordDisbursement(Long tontineId, Long roundId, Long beneficiaryId,
                                            BigDecimal amount, Currency currency) {
        String idempotencyKey = buildIdempotencyKey(tontineId, roundId, beneficiaryId);

        FinancialAccount tontineAccount = ledgerService.getOrCreateAccount(tontineId, FinancialAccountType.TONTINE, currency);
        FinancialAccount memberAccount = ledgerService.getOrCreateAccount(beneficiaryId, FinancialAccountType.MEMBER, currency);

        return ledgerService.record(
                idempotencyKey,
                EVENT_TYPE,
                idempotencyKey,
                "Versement round " + roundId + " tontine " + tontineId,
                currency,
                List.of(
                        new PostingLine(memberAccount.getId(), amount, BigDecimal.ZERO),
                        new PostingLine(tontineAccount.getId(), BigDecimal.ZERO, amount)
                ));
    }

    /**
     * Clé d'idempotence déterministe (même discipline que {@code
     * ContributionService.buildIdempotencyKey}, décision R3) — jamais
     * acceptée en entrée.
     */
    static String buildIdempotencyKey(Long tontineId, Long roundId, Long beneficiaryId) {
        return "disbursement:" + tontineId + ":" + roundId + ":" + beneficiaryId;
    }
}
