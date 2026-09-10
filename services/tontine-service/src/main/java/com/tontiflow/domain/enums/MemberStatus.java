package com.tontiflow.domain.enums;

/**
 * Statut de liaison d'un membre de tontine à un compte TontiFlow
 * authentifiable (décisions R18 D1/D5).
 *
 * <p>À ne pas confondre avec les drapeaux d'éligibilité de
 * {@code TontineMember} ({@code active}/{@code suspended}/{@code excluded}) :
 * {@code MemberStatus} concerne uniquement le rattachement à une identité
 * de compte ({@code TontineMember.accountId}, un {@code UUID}), pas le cycle
 * de vie d'éligibilité.</p>
 *
 * <ul>
 *   <li>{@link #PENDING} : le membre n'est pas encore lié à un compte
 *       TontiFlow ({@code accountId == null}). Il ne peut ni être
 *       bénéficiaire d'un round, ni recevoir de décaissement (décision D5).
 *       La liaison définitive sera réalisée par la personne elle-même via
 *       un futur mécanisme d'invitation (hors périmètre R19).</li>
 *   <li>{@link #ACTIVE} : le membre est lié à un compte TontiFlow
 *       ({@code accountId != null}). Comportement métier inchangé par
 *       rapport à l'existant.</li>
 * </ul>
 *
 * <p>Mapping JPA volontairement {@code STRING} (jamais {@code ORDINAL}) :
 * robustesse au réordonnancement, cohérent avec la leçon tirée de
 * {@code RoundStatus} (migration V3).</p>
 */
public enum MemberStatus {
    PENDING,
    ACTIVE
}
