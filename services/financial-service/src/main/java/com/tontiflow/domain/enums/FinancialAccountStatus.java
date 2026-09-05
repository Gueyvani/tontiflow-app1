package com.tontiflow.domain.enums;

/**
 * Statut d'un {@link com.tontiflow.domain.model.FinancialAccount} (décision R2).
 *
 * <p>Mappé {@code @Enumerated(EnumType.STRING)} — jamais ORDINAL (règle R2).</p>
 */
public enum FinancialAccountStatus {

    /** Compte utilisable normalement pour enregistrer des écritures. */
    ACTIVE,

    /** Compte fermé : plus aucune nouvelle écriture ne doit lui être adressée. */
    CLOSED
}
