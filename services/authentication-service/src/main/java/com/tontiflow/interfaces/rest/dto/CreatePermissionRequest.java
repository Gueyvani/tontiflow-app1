package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Requête de création d'une permission RBAC.
 *
 * @param name nom unique de la permission
 */
public record CreatePermissionRequest(

        @NotBlank(message = "Le nom de la permission est obligatoire")
        String name
) {
}
