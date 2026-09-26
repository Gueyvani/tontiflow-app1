package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.AccountBalance;
import com.tontiflow.application.service.LedgerService;
import com.tontiflow.domain.enums.FinancialAccountType;
import com.tontiflow.interfaces.rest.dto.AccountBalanceResponse;
import com.tontiflow.interfaces.rest.dto.LedgerLineResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint interne (décision R7, symétrique à {@code ContributionController}/
 * {@code DisbursementController} — décisions R3/R6) : consultation en
 * lecture seule du solde d'un compte financier, jamais d'écriture.
 *
 * <p><b>Hors routage Gateway</b> (décision F-8b) et protégé par jeton de
 * service (décision F-8) — même raisonnement que {@code ContributionController}
 * (voir son Javadoc) : portée {@code ledger.read} exigée pour ces lectures.</p>
 *
 * <p>Ne crée jamais de {@code FinancialAccount} : voir Javadoc de {@link
 * LedgerService#getAccountBalance}.</p>
 */
@RestController
public class AccountBalanceController {

    private final LedgerService ledgerService;

    public AccountBalanceController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/internal/accounts/{ownerReference}/{accountType}/balance")
    public ResponseEntity<AccountBalanceResponse> getBalance(
            @PathVariable Long ownerReference, @PathVariable FinancialAccountType accountType) {
        AccountBalance balance = ledgerService.getAccountBalance(ownerReference, accountType);
        return ResponseEntity.ok(new AccountBalanceResponse(
                balance.ownerReference(), balance.accountType(), balance.currency(), balance.balance()));
    }

    /**
     * Relevé de compte (décision R10, symétrique à {@link #getBalance}) :
     * détail des écritures derrière le solde. Lecture pure, aucune création
     * de compte.
     */
    @GetMapping("/internal/accounts/{ownerReference}/{accountType}/lines")
    public ResponseEntity<List<LedgerLineResponse>> getLines(
            @PathVariable Long ownerReference, @PathVariable FinancialAccountType accountType) {
        List<LedgerLineResponse> lines = ledgerService.getAccountStatement(ownerReference, accountType).stream()
                .map(LedgerLineResponse::from)
                .toList();
        return ResponseEntity.ok(lines);
    }
}
