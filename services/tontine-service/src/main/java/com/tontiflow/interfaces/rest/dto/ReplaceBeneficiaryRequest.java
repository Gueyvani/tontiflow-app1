package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Requête de remplacement du bénéficiaire d'un round.
 *
 * <p>L'auteur du remplacement ({@code actor}) n'est volontairement pas un
 * champ de cette requête : il est dérivé de l'identité JWT authentifiée
 * (voir {@code TontineRoundController}), jamais fourni par l'appelant, pour
 * que l'historique d'audit ({@code RoundRotationHistory.updatedBy}) reste
 * fiable.</p>
 */
public record ReplaceBeneficiaryRequest(
        @NotNull Long newBeneficiaryId,
        @NotBlank String reason
) {
}
