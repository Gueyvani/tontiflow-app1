package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Détail d'une écriture pour un relevé de compte (décision R10, §21 du
 * rapport d'inspection R9 : {@code LedgerLineRepository.findByFinancialAccount_Id}
 * existait déjà, jamais lu). Construit à l'intérieur de la transaction de
 * lecture de {@link LedgerService} (accès à {@code LedgerLine.getJournalEntry()},
 * chargement paresseux, avant fermeture de la session) — jamais construit
 * en dehors, pour ne dépendre d'aucun comportement implicite de session
 * ouverte (Open Session In View).
 *
 * <p>N'expose jamais {@code LedgerLine}/{@code JournalEntry} ni leurs
 * identifiants internes — mêmes principes que {@link AccountBalance}
 * (décision R7).</p>
 */
public record LedgerLineDetail(String eventType, String description, BigDecimal debit, BigDecimal credit,
                                Currency currency, Instant createdAt) {
}
