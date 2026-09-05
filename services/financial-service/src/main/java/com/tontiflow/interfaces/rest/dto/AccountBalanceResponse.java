package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountType;

import java.math.BigDecimal;

/**
 * Réponse de consultation de solde (décision R7) : n'expose que des valeurs
 * dérivées, jamais {@code FinancialAccount}/{@code JournalEntry}/{@code
 * LedgerLine} directement (même discipline que {@code
 * RecordContributionRequest}/{@code RecordDisbursementRequest}, décisions
 * R3/R6).
 */
public record AccountBalanceResponse(Long ownerReference, FinancialAccountType accountType, Currency currency,
                                      BigDecimal balance) {
}
