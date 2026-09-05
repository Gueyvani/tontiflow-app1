package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.enums.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Contrat interne (décision R6, symétrique à {@code RecordContributionRequest}
 * — décision R3) entre {@code tontine-service} et {@code financial-service}
 * pour l'enregistrement d'un versement au bénéficiaire d'un round.
 *
 * <p>Aucune {@code idempotencyKey} acceptée ici — même raisonnement que
 * Contribution (défense en profondeur, {@code DisbursementService} la
 * reconstruit lui-même de façon déterministe).</p>
 */
public record RecordDisbursementRequest(

        @NotNull
        Long tontineId,

        @NotNull
        Long roundId,

        @NotNull
        Long beneficiaryId,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false)
        BigDecimal amount,

        @NotNull
        Currency currency
) {
}
