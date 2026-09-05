package com.tontiflow.interfaces.rest.dto;

import java.math.BigDecimal;

/**
 * Réponse de confirmation (décision R6, symétrique à {@code
 * ContributionResponse} — décision R3) : n'expose que des identifiants du
 * domaine tontine.
 */
public record DisbursementResponse(
        Long tontineId,
        Long roundId,
        Long beneficiaryId,
        BigDecimal amount
) {
}
