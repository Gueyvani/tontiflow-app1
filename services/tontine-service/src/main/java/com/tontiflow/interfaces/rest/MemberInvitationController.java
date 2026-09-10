package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.application.service.MemberInvitationService;
import com.tontiflow.domain.model.MemberInvitation;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.interfaces.rest.dto.ClaimInvitationRequest;
import com.tontiflow.interfaces.rest.dto.MemberInvitationResponse;
import com.tontiflow.interfaces.rest.dto.MemberResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints d'invitation de liaison membre ↔ compte TontiFlow.
 *
 * <ul>
 *   <li>Génération (R20-B) : réservée au créateur de la tontine
 *       ({@code TontineApplicationService.requireCreator}).</li>
 *   <li>Revendication (R20-C) : ouverte à tout utilisateur authentifié — le
 *       code d'invitation <b>est</b> l'autorisation ; l'identité vient du JWT
 *       ({@code sub}), jamais du corps.</li>
 * </ul>
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

    /**
     * Revendique un membre {@code PENDING} de la tontine à partir d'un code
     * d'invitation : lie le membre au compte du JWT et le fait passer
     * {@code ACTIVE}. Consommation atomique et à usage unique.
     *
     * <p>Le corps ne contient que le code — aucun {@code accountId},
     * {@code userId} ou {@code memberId} n'est accepté. Toute invitation
     * invalide (inexistante, expirée, consommée, membre non-{@code PENDING},
     * incohérence de tontine, course perdue, mono-participation) renvoie un
     * {@code 409} générique « Invitation invalide. ».</p>
     *
     * @return {@code 200 OK} avec le {@link MemberResponse} du membre devenu {@code ACTIVE}
     */
    @PostMapping("/{tontineId}/members/claim")
    public ResponseEntity<MemberResponse> claim(
            @PathVariable Long tontineId, @Valid @RequestBody ClaimInvitationRequest request,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineMember member = memberInvitationService.claim(tontineId, request.code(), caller.userId());
        return ResponseEntity.ok(MemberResponse.from(member));
    }
}
