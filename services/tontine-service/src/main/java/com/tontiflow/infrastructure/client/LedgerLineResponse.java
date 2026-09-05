package com.tontiflow.infrastructure.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Réponse de {@code financial-service} à une consultation de relevé
 * (décision R10) — type local à {@code tontine-service}, sans dépendance de
 * compilation vers {@code financial-service} (même principe que {@code
 * AccountBalanceResponse}, décision R7).
 */
public record LedgerLineResponse(String eventType, String description, BigDecimal debit, BigDecimal credit,
                                  String currency, Instant createdAt) {
}
