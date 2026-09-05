package com.tontiflow.interfaces.rest.dto;

/**
 * Réponse renvoyée après une authentification réussie ou un renouvellement
 * de session (login et refresh partagent la même forme de réponse).
 *
 * @param accessToken  JWT signé RS256, généré par {@code AccessTokenService}
 * @param tokenType    schéma d'utilisation du token (toujours {@code "Bearer"})
 * @param expiresIn    durée de vie de l'Access Token en secondes (issue de {@code JwtProperties})
 * @param refreshToken Refresh Token brut, à conserver par le client pour renouveler la session
 *                     ({@code POST /api/v1/auth/refresh}) — jamais persisté en clair côté serveur
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken
) {
}
