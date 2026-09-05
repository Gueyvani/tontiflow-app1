package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.DisbursementService;
import com.tontiflow.interfaces.rest.dto.RecordDisbursementRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint interne (décision R6, symétrique à {@code ContributionController}
 * — décision R3) : reçoit une demande de versement déjà validée métier par
 * {@code tontine-service}. Hors routage Gateway par construction, même
 * raisonnement documenté sur {@code ContributionController} — la Gateway ne
 * route que {@code Path=/api/v1/financials/**}, {@code /internal/**} n'y
 * correspond jamais.
 */
@RestController
public class DisbursementController {

    private final DisbursementService disbursementService;

    public DisbursementController(DisbursementService disbursementService) {
        this.disbursementService = disbursementService;
    }

    @PostMapping("/internal/disbursements")
    public ResponseEntity<Void> recordDisbursement(@Valid @RequestBody RecordDisbursementRequest request) {
        disbursementService.recordDisbursement(
                request.tontineId(), request.roundId(), request.beneficiaryId(), request.amount(), request.currency());
        return ResponseEntity.ok().build();
    }
}
