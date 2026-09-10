package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.model.TontineMember;

import java.util.UUID;

/**
 * Représentation HTTP d'un {@link TontineMember}.
 *
 * <ul>
 *   <li>{@code id} : identifiant interne de la participation (référencé par
 *       les rounds et financial-service). Jamais l'identité du compte.</li>
 *   <li>{@code userId} : champ historique déprécié (audits R17/R18), conservé
 *       transitoirement pour compatibilité.</li>
 *   <li>{@code accountId} : UUID du compte TontiFlow lié, {@code null} si le
 *       membre est {@code PENDING}.</li>
 *   <li>{@code status} : {@code PENDING} (non lié à un compte) ou {@code ACTIVE}.</li>
 *   <li>{@code displayName}/{@code invitedPhone} : aides à l'invitation
 *       saisies par le créateur (optionnelles). Aucun secret d'invitation
 *       (code / hash) n'est jamais exposé ici.</li>
 * </ul>
 */
public record MemberResponse(
        Long id,
        Long tontineId,
        Long userId,
        UUID accountId,
        String status,
        String displayName,
        String invitedPhone,
        int sequentialOrder,
        boolean active
) {
    public static MemberResponse from(TontineMember member) {
        return new MemberResponse(
                member.getId(), member.getTontineId(), member.getUserId(),
                member.getAccountId(), member.getStatus().name(),
                member.getDisplayName(), member.getInvitedPhone(),
                member.getSequentialOrder(), member.isActive());
    }
}
