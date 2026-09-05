package com.tontiflow.interfaces.rest.dto;

import java.util.Set;
import java.util.UUID;

/**
 * Représentation exposée d'un rôle RBAC — jamais l'entité JPA directement.
 *
 * @param id          identifiant du rôle
 * @param name        nom du rôle
 * @param permissions noms des permissions associées à ce rôle
 */
public record RoleResponse(UUID id, String name, Set<String> permissions) {
}
