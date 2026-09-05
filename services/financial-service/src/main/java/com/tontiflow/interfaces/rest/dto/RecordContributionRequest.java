package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.enums.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Contrat interne (décision R3) entre {@code tontine-service} et {@code
 * financial-service} pour l'enregistrement d'une contribution.
 *
 * <p><b>Aucune {@code idempotencyKey} n'est acceptée ici</b> — décision R3 :
 * plutôt que de faire confiance à une valeur transmise par l'appelant
 * (même interne), {@code financial-service} la reconstruit lui-même de
 * façon déterministe à partir de {@code tontineId}/{@code roundId}/{@code
 * memberId} (défense en profondeur — les deux services convergent
 * indépendamment vers la même clé, sans jamais avoir à s'y fier).</p>
 *
 * <p>{@code amount} est considéré comme une donnée déjà validée par {@code
 * tontine-service} (dérivée de {@code TontineRound.amount}, jamais fournie
 * par un client externe), mais reste techniquement revalidée ici
 * ({@code @DecimalMin} + validation {@link com.tontiflow.application.service.LedgerService}).</p>
 */
public record RecordContributionRequest(

        @NotNull
        Long tontineId,

        @NotNull
        Long roundId,

        @NotNull
        Long memberId,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false)
        BigDecimal amount,

        @NotNull
        Currency currency
) {
}
