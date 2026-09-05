package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.application.service.ContributionApplicationService;
import com.tontiflow.application.service.DisbursementApplicationService;
import com.tontiflow.application.service.TontineRoundApplicationService;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.interfaces.rest.dto.ContributionRequest;
import com.tontiflow.interfaces.rest.dto.ContributionResponse;
import com.tontiflow.interfaces.rest.dto.DisbursementResponse;
import com.tontiflow.interfaces.rest.dto.ReplaceBeneficiaryRequest;
import com.tontiflow.interfaces.rest.dto.RotationHistoryResponse;
import com.tontiflow.interfaces.rest.dto.TontineRoundResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints REST exposant {@link TontineRoundApplicationService} : la
 * logique d'attribution/remplacement de bénéficiaire de round existait déjà
 * (moteur d'éligibilité, stratégies de rotation) mais n'était accessible par
 * aucune route HTTP avant ce contrôleur.
 *
 * <p>Aucune logique métier ici : chaque méthode délègue intégralement à
 * {@link TontineRoundApplicationService}. Ne crée ni ne modélise l'agrégat
 * {@code Tontine} lui-même (absent du domaine actuel) — ces endpoints
 * supposent qu'un round existe déjà.</p>
 */
@RestController
@RequestMapping("/api/v1/tontines")
public class TontineRoundController {

    private final TontineRoundApplicationService tontineRoundApplicationService;
    private final ContributionApplicationService contributionApplicationService;
    private final DisbursementApplicationService disbursementApplicationService;

    public TontineRoundController(TontineRoundApplicationService tontineRoundApplicationService,
                                   ContributionApplicationService contributionApplicationService,
                                   DisbursementApplicationService disbursementApplicationService) {
        this.tontineRoundApplicationService = tontineRoundApplicationService;
        this.contributionApplicationService = contributionApplicationService;
        this.disbursementApplicationService = disbursementApplicationService;
    }

    /**
     * Enregistre administrativement la contribution d'un membre pour ce
     * round (décision R3, modèle créateur-administré — cf. rapport
     * d'inspection : aucun lien fiable {@code TontineMember.userId} ↔ JWT
     * n'existe actuellement, un flux self-service est donc hors périmètre).
     * Le montant provient exclusivement de {@code TontineRound.amount},
     * jamais du corps de la requête. Idempotent (délégué à {@code
     * financial-service}) : un appel répété avec le même {@code memberId}
     * sur le même round ne crée jamais une seconde écriture financière.
     *
     * @return {@code 200 OK} avec la confirmation (aucun détail financier interne exposé)
     */
    @PostMapping("/{tontineId}/rounds/{roundId}/contributions")
    public ResponseEntity<ContributionResponse> recordContribution(
            @PathVariable Long tontineId, @PathVariable Long roundId,
            @Valid @RequestBody ContributionRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineRound round = contributionApplicationService.recordContribution(
                tontineId, roundId, request.memberId(), caller.userId(), authorizationHeader);
        return ResponseEntity.ok(new ContributionResponse(tontineId, roundId, request.memberId(), round.getAmount()));
    }

    /**
     * Enregistre administrativement le versement au bénéficiaire de ce round
     * (décision R6, symétrique à {@link #recordContribution} — décision R3,
     * même modèle créateur-administré). Aucun champ dans le corps de la
     * requête : le bénéficiaire ({@code TontineRound.beneficiaryId}, déjà
     * assigné exclusivement parmi les membres de la tontine) et le montant
     * ({@code TontineRound.amount}) sont entièrement dérivés côté serveur.
     * Idempotent (délégué à {@code financial-service}).
     *
     * @return {@code 200 OK} avec la confirmation (aucun détail financier interne exposé)
     */
    @PostMapping("/{tontineId}/rounds/{roundId}/disbursements")
    public ResponseEntity<DisbursementResponse> recordDisbursement(
            @PathVariable Long tontineId, @PathVariable Long roundId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineRound round = disbursementApplicationService.recordDisbursement(
                tontineId, roundId, caller.userId(), authorizationHeader);
        return ResponseEntity.ok(
                new DisbursementResponse(tontineId, roundId, round.getBeneficiaryId(), round.getAmount()));
    }

    /**
     * Attribue le bénéficiaire du round selon la stratégie de rotation
     * configurée et le moteur d'éligibilité. Idempotent si le round est déjà
     * {@code ASSIGNED}/{@code COMPLETED} (voir {@link TontineRoundApplicationService}).
     *
     * @return {@code 200 OK} avec le round mis à jour
     */
    @PostMapping("/{tontineId}/rounds/{roundId}/assign-beneficiary")
    public ResponseEntity<TontineRoundResponse> assignBeneficiary(
            @PathVariable Long tontineId, @PathVariable Long roundId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineRound round = tontineRoundApplicationService.assignNextRoundBeneficiary(
                tontineId, roundId, caller.userId());
        return ResponseEntity.ok(TontineRoundResponse.from(round));
    }

    /**
     * Remplace le bénéficiaire d'un round déjà attribué, avec conservation
     * d'un historique d'audit. L'auteur du remplacement est dérivé de
     * l'identité JWT authentifiée, jamais du corps de la requête.
     *
     * @return {@code 200 OK} avec le round mis à jour
     */
    @PutMapping("/rounds/{roundId}/beneficiary")
    public ResponseEntity<TontineRoundResponse> replaceBeneficiary(
            @PathVariable Long roundId, @Valid @RequestBody ReplaceBeneficiaryRequest request,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineRound round = tontineRoundApplicationService.replaceBeneficiary(
                roundId, request.newBeneficiaryId(), request.reason(), caller.username(), caller.userId());
        return ResponseEntity.ok(TontineRoundResponse.from(round));
    }

    /**
     * Liste les rounds d'une tontine (décision A3). Réservé au créateur
     * (décision B1).
     */
    @GetMapping("/{tontineId}/rounds")
    public ResponseEntity<List<TontineRoundResponse>> listRounds(
            @PathVariable Long tontineId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        List<TontineRoundResponse> rounds = tontineRoundApplicationService.listRounds(tontineId, caller.userId())
                .stream()
                .map(TontineRoundResponse::from)
                .toList();
        return ResponseEntity.ok(rounds);
    }

    /**
     * Round « courant » d'une tontine (décision C2) — voir
     * {@link TontineRoundApplicationService#getCurrentRound} pour
     * l'interprétation appliquée. Déclaré avant {@code /{roundId}} : Spring
     * priorise le segment littéral {@code current} sur le segment variable.
     */
    @GetMapping("/{tontineId}/rounds/current")
    public ResponseEntity<TontineRoundResponse> getCurrentRound(
            @PathVariable Long tontineId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineRound round = tontineRoundApplicationService.getCurrentRound(tontineId, caller.userId());
        return ResponseEntity.ok(TontineRoundResponse.from(round));
    }

    /**
     * Lit un round précis d'une tontine (décision A3). Réservé au créateur
     * (décision B1).
     */
    @GetMapping("/{tontineId}/rounds/{roundId}")
    public ResponseEntity<TontineRoundResponse> getRound(
            @PathVariable Long tontineId, @PathVariable Long roundId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineRound round = tontineRoundApplicationService.getRound(tontineId, roundId, caller.userId());
        return ResponseEntity.ok(TontineRoundResponse.from(round));
    }

    /**
     * Historique des remplacements de bénéficiaire pour ce round (décision
     * R9) — expose {@code RoundRotationHistory}, déjà écrite par {@link
     * #replaceBeneficiary} mais jamais relue jusqu'ici. Réservé au créateur
     * (même contrôle que {@link #getRound}).
     */
    @GetMapping("/{tontineId}/rounds/{roundId}/rotation-history")
    public ResponseEntity<List<RotationHistoryResponse>> listRotationHistory(
            @PathVariable Long tontineId, @PathVariable Long roundId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        List<RotationHistoryResponse> history = tontineRoundApplicationService
                .listRotationHistory(tontineId, roundId, caller.userId())
                .stream()
                .map(RotationHistoryResponse::from)
                .toList();
        return ResponseEntity.ok(history);
    }
}
