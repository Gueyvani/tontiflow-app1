package com.tontiflow.interfaces.rest.dto;

import java.math.BigDecimal;

/**
 * Réponse de consultation de solde (décision R7) : n'expose que des
 * identifiants du domaine tontine, jamais un identifiant interne de
 * {@code financial-service} ({@code FinancialAccount}/{@code JournalEntry}/
 * {@code LedgerLine}) — même discipline que {@code ContributionResponse}/
 * {@code DisbursementResponse} (décisions R3/R6).
 */
public record TontineBalanceResponse(Long tontineId, String currency, BigDecimal balance) {
}
