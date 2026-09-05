package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Requête de connexion (tentative d'authentification).
 *
 * @param email    email du compte
 * @param password mot de passe en clair fourni pour la tentative
 */
public record LoginRequest(

        @NotBlank(message = "L'email est obligatoire")
        @Email(message = "L'email doit etre une adresse valide")
        String email,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, message = "Le mot de passe doit contenir au moins 8 caracteres")
        String password
) {
}
