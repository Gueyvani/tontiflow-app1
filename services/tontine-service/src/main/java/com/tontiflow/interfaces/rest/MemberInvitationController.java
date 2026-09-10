package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.application.service.MemberInvitationService;
import com.tontiflow.domain.model.MemberInvitation;
import com.tontiflow.interfaces.rest.dto.MemberInvitationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Génération d'une invitation de liaison pour un membre {@code PENDING}
 * (R20-B). Opération d'administration de tontine : réservée au créateur
 * (contrôle d'accès identique aux autres endpoints, via
 * {@code TontineApplicationService.requireCreator} dans le service).
 *
 * <p>Ne fait pas passer le membre à {@code ACTIVE} et ne consomme aucune
 * invitation : la revendication ({@code POST .../members/claim}) est traitée
 * en R20-C.</p>
 */
@RestController
@RequestMapping("/api/v1/tontines")
public class MemberInvitationController {

    private final MemberInvitationService memberInvitationService;

    public MemberInvitationController(MemberInvitationService memberInvitationService) {
        this.memberInvitationService = memberInvitationService;
    }

    /**
     * Génère (ou régénère) l'unique invitation active d'un membre. Le code
     * brut n'est présent que dans cette réponse, une seule fois.
     *
     * @return {@code 200 OK} avec {@link MemberInvitationResponse}
     */
    @PostMapping("/{tontineId}/members/{memberId}/invitation")
    public ResponseEntity<MemberInvitationResponse> generateInvitation(
            @PathVariable Long tontineId, @PathVariable Long memberId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        MemberInvitation invitation =
                memberInvitationService.generateInvitation(tontineId, memberId, caller.userId());
        return ResponseEntity.ok(MemberInvitationResponse.from(invitation));
    }
}
