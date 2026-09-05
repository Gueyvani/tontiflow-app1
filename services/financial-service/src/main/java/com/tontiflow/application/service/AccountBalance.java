package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountType;

import java.math.BigDecimal;

/**
 * Résultat de la consultation du solde d'un compte financier (décision R7,
 * §21 du rapport d'inspection Étape 1 : {@link LedgerService#computeBalance}
 * existait déjà, testé, mais n'était exposé par aucun contrôleur).
 *
 * <p>Ne référence jamais {@link com.tontiflow.domain.model.FinancialAccount}
 * directement — même découplage entre appelant et couche de persistance que
 * {@link PostingLine} (décision R2).</p>
 */
public record AccountBalance(Long ownerReference, FinancialAccountType accountType, Currency currency,
                              BigDecimal balance) {
}
