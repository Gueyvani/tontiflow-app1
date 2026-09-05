package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountType;
import com.tontiflow.domain.model.FinancialAccount;
import com.tontiflow.domain.model.JournalEntry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cas d'usage métier « contribution » (décision R3) — seul point d'entrée
 * du flux tontine → financial. Traduit une demande de contribution en une
 * écriture Ledger équilibrée via {@link LedgerService}, jamais en accédant
 * directement aux repositories {@code JournalEntry}/{@code LedgerLine}
 * (LedgerService reste l'unique source d'écriture, décision R2/R3).
 *
 * <p><b>Convention Débit/Crédit</b> (décision R3, §13) : pour une
 * contribution reçue d'un membre vers le fonds de la tontine —
 * <b>Débit = compte {@code TONTINE}</b> (destination, l'actif « fonds
 * détenu » augmente), <b>Crédit = compte {@code MEMBER}</b> (source, la
 * position du membre est créditée de sa contribution). Cohérent avec
 * l'exemple documenté en Phase R et l'exemple conceptuel de la Phase R3.</p>
 */
@Service
public class ContributionService {

    private static final String EVENT_TYPE = "CONTRIBUTION_RECORDED";

    private final LedgerService ledgerService;

    public ContributionService(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * Enregistre une contribution : obtient/crée les deux comptes
     * financiers nécessaires puis comptabilise une écriture équilibrée.
     * Idempotent (délégué à {@link LedgerService#record}) — un second
     * appel avec les mêmes {@code tontineId}/{@code roundId}/{@code
     * memberId} ne crée jamais une seconde écriture.
     */
    public JournalEntry recordContribution(Long tontineId, Long roundId, Long memberId,
                                            BigDecimal amount, Currency currency) {
        String idempotencyKey = buildIdempotencyKey(tontineId, roundId, memberId);

        FinancialAccount tontineAccount = ledgerService.getOrCreateAccount(tontineId, FinancialAccountType.TONTINE, currency);
        FinancialAccount memberAccount = ledgerService.getOrCreateAccount(memberId, FinancialAccountType.MEMBER, currency);

        return ledgerService.record(
                idempotencyKey,
                EVENT_TYPE,
                idempotencyKey,
                "Contribution round " + roundId + " tontine " + tontineId,
                currency,
                List.of(
                        new PostingLine(tontineAccount.getId(), amount, BigDecimal.ZERO),
                        new PostingLine(memberAccount.getId(), BigDecimal.ZERO, amount)
                ));
    }

    /**
     * Clé d'idempotence déterministe (décision R3, §15/§19) — reconstruite
     * ici indépendamment de toute valeur transmise par l'appelant, jamais
     * acceptée en entrée : deux services calculant indépendamment la même
     * formule convergent naturellement vers la même clé, sans jamais avoir
     * à se faire confiance sur une chaîne libre.
     */
    static String buildIdempotencyKey(Long tontineId, Long roundId, Long memberId) {
        return "contribution:" + tontineId + ":" + roundId + ":" + memberId;
    }
}
