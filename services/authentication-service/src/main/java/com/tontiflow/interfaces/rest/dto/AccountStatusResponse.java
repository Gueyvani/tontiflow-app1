package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.enums.AccountStatus;

import java.util.UUID;

/**
 * Représentation exposée du statut d'un compte après une transition
 * administrative — jamais l'entité JPA directement, et ne contient
 * volontairement jamais le motif fourni (décision R21-RD, D4/D8).
 *
 * @param accountId identifiant du compte
 * @param email     email du compte
 * @param status    statut désormais en vigueur
 */
public record AccountStatusResponse(UUID accountId, String email, AccountStatus status) {
}
