package com.tontiflow.interfaces.rest.dto;

import java.math.BigDecimal;

/**
 * Réponse de confirmation (décision R3) : n'expose que des identifiants du
 * domaine tontine, jamais un identifiant interne de {@code financial-service}
 * ({@code FinancialAccount}/{@code JournalEntry}) — le client n'a jamais
 * besoin de connaître ces détails d'implémentation.
 */
public record ContributionResponse(
        Long tontineId,
        Long roundId,
        Long memberId,
        BigDecimal amount
) {
}
