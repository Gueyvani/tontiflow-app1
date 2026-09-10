package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.model.MemberInvitation;

import java.time.LocalDateTime;

/**
 * Réponse de génération d'invitation — renvoyée <b>une seule fois</b> au
 * créateur autorisé, qui transmet le code hors bande (aucun envoi automatique
 * SMS/email : {@code notification-service} est hors périmètre).
 *
 * <p>N'expose que le strict nécessaire : {@code memberId}, le {@code code}
 * brut, et {@code expiresAt}. Jamais le {@code codeHash}, ni {@code consumedAt},
 * ni aucune donnée de compte.</p>
 */
public record MemberInvitationResponse(
        Long memberId,
        String code,
        LocalDateTime expiresAt
) {
    public static MemberInvitationResponse from(MemberInvitation invitation) {
        return new MemberInvitationResponse(
                invitation.getTontineMemberId(),
                invitation.getRawCode(),
                invitation.getExpiresAt());
    }
}
