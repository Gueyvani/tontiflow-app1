package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.enums.RotationType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Le créateur n'est volontairement pas un champ de cette requête : il est
 * dérivé de l'identité JWT authentifiée (voir {@code TontineController}).
 *
 * <p>Porte également les champs obligatoires de {@code TontineConfig}
 * (décision métier validée : la configuration est créée automatiquement,
 * de façon transactionnelle, avec la tontine — voir {@code TontineApplicationService}).
 * {@code reorganisationAllowed} est le seul champ facultatif de la
 * configuration : {@code true} lorsqu'il est absent.</p>
 */
public record CreateTontineRequest(
        @NotBlank String name,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal contributionAmount,
        @NotNull ContributionFrequency contributionFrequency,
        @NotNull @Positive Integer maxMembers,
        @NotNull RotationType rotationType,
        @NotNull NonCompliantBehavior nonCompliantBehavior,
        Boolean reorganisationAllowed
) {
}
