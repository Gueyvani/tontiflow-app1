package com.tontiflow.application.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ligne d'écriture à comptabiliser, telle que fournie à {@link LedgerService}
 * — ne référence un compte financier que par son identifiant, jamais par
 * l'entité elle-même (découplage entre l'appelant et la couche de
 * persistance).
 *
 * <p>Exactement un des deux montants doit être strictement positif, l'autre
 * exactement zéro — validé par {@link LedgerService}, redondant avec la
 * contrainte {@code CHECK} en base (V1), jamais une alternative à elle.</p>
 */
public record PostingLine(UUID financialAccountId, BigDecimal debit, BigDecimal credit) {
}
