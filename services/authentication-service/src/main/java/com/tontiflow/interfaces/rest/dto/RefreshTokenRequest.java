package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête de renouvellement de session via un Refresh Token.
 *
 * @param refreshToken token brut précédemment émis (par login ou par un refresh antérieur)
 */
public record RefreshTokenRequest(

        @NotBlank(message = "Le refresh token est obligatoire")
        String refreshToken
) {
}
