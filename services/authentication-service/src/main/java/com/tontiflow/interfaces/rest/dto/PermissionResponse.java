package com.tontiflow.interfaces.rest.dto;

import java.util.UUID;

/**
 * Représentation exposée d'une permission RBAC — jamais l'entité JPA directement.
 *
 * @param id   identifiant de la permission
 * @param name nom de la permission
 */
public record PermissionResponse(UUID id, String name) {
}
