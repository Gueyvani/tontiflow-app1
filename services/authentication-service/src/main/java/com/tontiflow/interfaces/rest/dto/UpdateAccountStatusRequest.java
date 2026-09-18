package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.enums.AccountStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Requête de changement administratif du statut d'un compte (décision
 * R21-RD, D4).
 *
 * <p>{@code reason} est obligatoire pour toute transition effective (rejeté
 * s'il est absent, vide ou composé uniquement d'espaces) — y compris dans le
 * cas idempotent (statut déjà appliqué), par cohérence de contrat, même si
 * aucune écriture n'en résulte alors. Longueur maximale de 255 caractères,
 * alignée sur le seul précédent de champ texte libre de ce service
 * ({@code email VARCHAR(255)}, {@code AuthAccount}).</p>
 *
 * @param status statut cible
 * @param reason motif de la transition, jamais exposé dans une réponse HTTP
 *               ni journalisé
 */
public record UpdateAccountStatusRequest(

        @NotNull(message = "Le statut cible est obligatoire")
        AccountStatus status,

        @NotBlank(message = "Le motif est obligatoire")
        @Size(max = 255, message = "Le motif ne doit pas depasser 255 caracteres")
        String reason
) {
}
