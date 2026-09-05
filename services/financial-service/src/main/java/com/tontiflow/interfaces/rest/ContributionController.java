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
 * <p><b>Hors routage Gateway par construction</b> : la Gateway ne route
 * que {@code Path=/api/v1/financials/**} vers ce service (voir {@code
 * api-gateway/application.yml}) ; ce contrôleur est monté sous {@code
 * /internal/**}, un préfixe pour lequel la Gateway ne déclare aucun
 * prédicat — toute requête externe vers {@code /internal/contributions}
 * via la Gateway reçoit un 404 de la Gateway elle-même, sans jamais
 * atteindre ce service. Reste protégé par la même chaîne JWT que le reste
 * de {@code financial-service} ({@code SecurityConfig}, {@code
 * anyRequest().authenticated()}, inchangée) : {@code tontine-service}
 * transmet le JWT de l'appelant original lors de l'appel service-à-service
 * (défense en profondeur, cf. Javadoc de {@code FinancialServiceClient}
 * côté tontine-service).</p>
 *
 * <p><b>Risque résiduel documenté honnêtement</b> (décision R3, §26) :
 * aucune primitive cryptographique service-à-service n'est introduite ; un
 * accès réseau direct au port de {@code financial-service} (contournant la
 * Gateway) resterait capable d'atteindre cet endpoint muni d'un JWT valide
 * — même modèle de confiance implicite que celui déjà en vigueur pour tous
 * les autres services de ce monorepo, pas un risque nouveau introduit ici.</p>
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
