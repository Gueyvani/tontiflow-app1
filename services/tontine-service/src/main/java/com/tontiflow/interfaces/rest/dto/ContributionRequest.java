package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Requête d'enregistrement administratif d'une contribution (décision R3,
 * modèle créateur-administré). Volontairement minimal : ni {@code amount}
 * ni {@code idempotencyKey} — le montant vient de {@code
 * TontineRound.amount}, la clé d'idempotence est construite côté serveur
 * (§12/§15) — jamais des valeurs que le client pourrait choisir librement.
 */
public record ContributionRequest(
        @NotNull @Positive Long memberId
) {
}
