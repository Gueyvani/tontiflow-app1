package com.tontiflow.domain.enums;

/**
 * Type d'un {@link com.tontiflow.domain.model.FinancialAccount} (décision R2).
 *
 * <p>Seuls les deux types réellement nécessaires au premier flux de
 * contribution (tontine-service → financial-service, Phase R2) sont
 * déclarés — {@code SYSTEM} n'est volontairement pas ajouté ici faute
 * d'un usage réel actuel (règle "ne pas inventer des types inutiles").</p>
 *
 * <p>Mappé {@code @Enumerated(EnumType.STRING)} — jamais ORDINAL (règle R2).</p>
 */
public enum FinancialAccountType {

    /** Compte représentant le fonds mutualisé d'une tontine (un par {@code tontineId}). */
    TONTINE,

    /** Compte représentant la position d'un membre au sein d'une tontine (un par membre). */
    MEMBER
}
