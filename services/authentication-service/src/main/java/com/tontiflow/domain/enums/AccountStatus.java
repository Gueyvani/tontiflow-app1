package com.tontiflow.domain.enums;

/**
 * Statut d'un compte d'authentification ({@link com.tontiflow.domain.model.AuthAccount}).
 *
 * <p>Ce statut conditionne la capacité d'un compte à s'authentifier :
 * seul un compte {@link #ACTIVE} doit pouvoir obtenir un nouveau token
 * lors de l'implémentation du service d'authentification (phase ultérieure).</p>
 */
public enum AccountStatus {

    /** Compte utilisable normalement pour l'authentification. */
    ACTIVE,

    /** Compte temporairement bloqué (ex. : trop de tentatives échouées). */
    LOCKED,

    /** Compte désactivé de façon durable (ex. : suppression logique, décision administrative). */
    DISABLED
}
