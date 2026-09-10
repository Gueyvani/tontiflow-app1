package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Corps d'ajout d'un membre.
 *
 * <p>{@code userId} ({@code Long}) reste un champ historique/déprécié (audits
 * R17/R18) — il n'identifie aucun compte réel et ne doit pas servir d'identité
 * métier. {@code displayName} et {@code invitedPhone} sont <b>optionnels</b> :
 * simples aides à l'invitation figées à l'ajout, jamais un profil utilisateur
 * (cf. {@code UserProfile}). Le client ne peut pas fournir d'{@code accountId} :
 * la liaison à un compte se fera exclusivement via le mécanisme d'invitation
 * (R20-C).</p>
 */
public record AddMemberRequest(
        @NotNull @Positive Long userId,
        @PositiveOrZero int sequentialOrder,
        @Size(max = 255) String displayName,
        @Size(max = 32) String invitedPhone
) {
}
