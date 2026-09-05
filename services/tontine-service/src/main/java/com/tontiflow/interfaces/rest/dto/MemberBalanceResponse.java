package com.tontiflow.interfaces.rest.dto;

import java.math.BigDecimal;

/**
 * Réponse de consultation de solde d'un membre (décision R8, symétrique à
 * {@code TontineBalanceResponse} — décision R7) : n'expose que des
 * identifiants du domaine tontine, jamais un identifiant interne de
 * {@code financial-service}.
 */
public record MemberBalanceResponse(Long tontineId, Long memberId, String currency, BigDecimal balance) {
}
