package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de la revendication d'une invitation (R20-C).
 *
 * <p>Contient <b>uniquement</b> le code d'invitation. L'identité du
 * revendiquant provient exclusivement du JWT ({@code sub}) — jamais du corps :
 * aucun {@code accountId}, {@code userId}, {@code memberId}, {@code email} ni
 * {@code username}.</p>
 *
 * <p>Le code est normalisé côté service ({@code trim()} + {@code toUpperCase()})
 * avant validation de format et calcul du hash. La borne {@code @Size} n'est
 * qu'une protection défensive (éviter de hacher une chaîne arbitrairement
 * longue) ; la validation exacte de longueur/alphabet est faite dans le
 * service, un code mal formé étant traité comme une invitation invalide
 * (HTTP 409 générique, anti-énumération).</p>
 */
public record ClaimInvitationRequest(
        @NotBlank @Size(max = 64) String code
) {
}
