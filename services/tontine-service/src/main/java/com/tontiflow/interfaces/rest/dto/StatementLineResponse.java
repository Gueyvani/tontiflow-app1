package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.infrastructure.client.LedgerLineResponse;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Réponse de relevé de compte (décision R10) : n'expose que des valeurs
 * dérivées, jamais un identifiant interne de {@code financial-service} —
 * même discipline que {@code TontineBalanceResponse}/{@code
 * MemberBalanceResponse} (décisions R7/R8).
 */
public record StatementLineResponse(String eventType, String description, BigDecimal debit, BigDecimal credit,
                                     String currency, Instant createdAt) {
    public static StatementLineResponse from(LedgerLineResponse line) {
        return new StatementLineResponse(
                line.eventType(), line.description(), line.debit(), line.credit(), line.currency(), line.createdAt());
    }
}
