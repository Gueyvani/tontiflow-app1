package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.ContributionService;
import com.tontiflow.interfaces.rest.dto.RecordContributionRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint interne (décision R3, §9/§26) : reçoit une demande de
 * contribution déjà validée métier par {@code tontine-service} (créateur
 * authentifié, membre/round revalidés côté tontine).
 *
 * <p><b>Hors routage Gateway</b> (décision F-8b) : la Gateway ne déclare
 * plus aucune route vers {@code financial-service}, donc toute requête
 * externe vers {@code /internal/contributions} (ou vers un chemin de type
 * {@code /api/v1/financials/...}) reçoit un 404 de la Gateway elle-même,
 * sans jamais atteindre ce service.</p>
 *
 * <p><b>Authentification (décision F-8)</b> : seuls les appels de
 * {@code tontine-service} munis d'un jeton de service HS256 court, de portée
 * {@code ledger.write}, sont acceptés ({@code SecurityConfig}) ; un JWT
 * utilisateur n'est plus accepté.</p>
 */
@RestController
public class ContributionController {

    private final ContributionService contributionService;

    public ContributionController(ContributionService contributionService) {
        this.contributionService = contributionService;
    }

    @PostMapping("/internal/contributions")
    public ResponseEntity<Void> recordContribution(@Valid @RequestBody RecordContributionRequest request) {
        contributionService.recordContribution(
                request.tontineId(), request.roundId(), request.memberId(), request.amount(), request.currency());
        return ResponseEntity.ok().build();
    }
}
