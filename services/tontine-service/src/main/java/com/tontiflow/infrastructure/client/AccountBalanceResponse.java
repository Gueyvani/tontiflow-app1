package com.tontiflow.infrastructure.client;

import java.math.BigDecimal;

/**
 * Réponse de {@code financial-service} à une consultation de solde
 * (décision R7) — type local à {@code tontine-service}, sans dépendance de
 * compilation vers {@code financial-service} (même principe que {@code
 * RecordContributionPayload}/{@code RecordDisbursementPayload}, décisions
 * R3/R6). Seuls les champs réellement utilisés par l'appelant sont repris.
 */
public record AccountBalanceResponse(String currency, BigDecimal balance) {
}
