package com.tontiflow.domain.enums;

/**
 * IMPORTANT — CONTRAT DE PERSISTANCE (décision Q8, audit Phase Q).
 *
 * <p>{@code TontineConfig.rotationType} est stocké en colonne {@code
 * SMALLINT} ({@code tontine_config.rotation_type}, migration V1) sans
 * {@code @Enumerated} sur le champ JPA : le mapping est donc {@code
 * EnumType.ORDINAL} implicite. **L'ordre de déclaration ci-dessous fait donc
 * partie du contrat de base de données** — ne jamais réordonner ni insérer
 * une constante entre deux existantes.
 *
 * <p>La colonne porte une contrainte {@code CHECK (rotation_type BETWEEN 0
 * AND 2)} (V1), correspondant exactement aux 3 valeurs actuelles (0-2).
 * Ajouter une nouvelle constante nécessiterait une migration Flyway
 * élargissant cette contrainte — même mécanisme que celui appliqué à {@code
 * RoundStatus.BLOCKED} (migration V6). Aucune extension n'est prévue ni
 * effectuée ici.
 *
 * <p>Une conversion future vers {@code EnumType.STRING} (annotation
 * explicite + migration de données) reste une décision architecturale
 * séparée, non réalisée par ce commentaire ni par la Phase Q2.</p>
 */
public enum RotationType {
    SEQUENTIAL,
    RANDOM,
    MANUAL
}