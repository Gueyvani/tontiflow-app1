package com.tontiflow.security.jwt;

import java.util.Optional;

/**
 * Extrait un JWT depuis un header HTTP Authorization
 * utilisant le schéma Bearer.
 */
public final class BearerTokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Extrait le token depuis la valeur du header Authorization.
     *
     * @param authorizationHeader valeur du header Authorization
     * @return le JWT lorsqu'un Bearer Token valide est présent,
     *         sinon {@link Optional#empty()}
     */
    public Optional<String> extract(String authorizationHeader) {

        if (authorizationHeader == null
                || authorizationHeader.isBlank()) {
            return Optional.empty();
        }

        if (!authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }

        String token = authorizationHeader
                .substring(BEARER_PREFIX.length())
                .trim();

        if (token.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(token);
    }
}