package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.enums.RotationType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Remplacement complet (sémantique PUT) de la configuration existante d'une
 * tontine. Réservé au créateur de la tontine.
 */
public record UpdateTontineConfigRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal contributionAmount,
        @NotNull ContributionFrequency contributionFrequency,
        @NotNull @Positive Integer maxMembers,
        @NotNull RotationType rotationType,
        @NotNull NonCompliantBehavior nonCompliantBehavior,
        Boolean reorganisationAllowed
) {
}
