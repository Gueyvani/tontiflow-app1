package com.tontiflow;

import java.util.Set;
import java.util.UUID;

/**
 * Conteneur immuable représentant le contexte de sécurité
 * d'un utilisateur authentifié à partir d'un Token JWT valide.
 *
 * <p>Le contexte contient l'identité technique de l'utilisateur,
 * ses informations principales ainsi que ses rôles et permissions.</p>
 *
 * @param userId      identifiant unique de l'utilisateur
 * @param username    nom d'utilisateur
 * @param email       adresse email de l'utilisateur
 * @param roles       rôles attribués à l'utilisateur
 * @param permissions permissions attribuées à l'utilisateur
 */
public record UserContext(
        UUID userId,
        String username,
        String email,
        Set<String> roles,
        Set<String> permissions
) {

    /**
     * Constructeur compact garantissant l'immutabilité des collections.
     *
     * @param userId      identifiant unique de l'utilisateur
     * @param username    nom d'utilisateur
     * @param email       adresse email de l'utilisateur
     * @param roles       rôles de l'utilisateur
     * @param permissions permissions de l'utilisateur
     */
    public UserContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}