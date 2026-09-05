package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête de création d'un rôle RBAC.
 *
 * @param name nom unique du rôle
 */
public record CreateRoleRequest(

        @NotBlank(message = "Le nom du role est obligatoire")
        String name
) {
}
