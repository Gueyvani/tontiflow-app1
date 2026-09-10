package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.application.service.BalanceApplicationService;
import com.tontiflow.application.service.TontineApplicationService;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.client.AccountBalanceResponse;
import com.tontiflow.interfaces.rest.dto.AddMemberRequest;
import com.tontiflow.interfaces.rest.dto.CreateTontineRequest;
import com.tontiflow.interfaces.rest.dto.MemberBalanceResponse;
import com.tontiflow.interfaces.rest.dto.MemberResponse;
import com.tontiflow.interfaces.rest.dto.StatementLineResponse;
import com.tontiflow.interfaces.rest.dto.TontineBalanceResponse;
import com.tontiflow.interfaces.rest.dto.TontineConfigResponse;
import com.tontiflow.interfaces.rest.dto.TontineResponse;
import com.tontiflow.interfaces.rest.dto.UpdateTontineConfigRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * Cycle de vie minimal de l'agrégat {@link Tontine} (création, ajout de
 * membre) — périmètre explicitement approuvé pour cette sous-phase.
 * Ne couvre ni la configuration ({@code TontineConfig}) ni la création de
 * round : hors périmètre, documenté dans le rapport de modélisation.
 *
 * <p>Le créateur d'une tontine est dérivé de l'identité JWT authentifiée,
 * jamais du corps de la requête.</p>
 */
@RestController
@RequestMapping("/api/v1/tontines")
public class TontineController {

    private final TontineApplicationService tontineApplicationService;
    private final BalanceApplicationService balanceApplicationService;

    public TontineController(TontineApplicationService tontineApplicationService,
                              BalanceApplicationService balanceApplicationService) {
        this.tontineApplicationService = tontineApplicationService;
        this.balanceApplicationService = balanceApplicationService;
    }

    @PostMapping
    public ResponseEntity<TontineResponse> createTontine(
            @Valid @RequestBody CreateTontineRequest request, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        Tontine tontine = tontineApplicationService.createTontine(
                request.name(), caller.userId(), request.contributionAmount(), request.contributionFrequency(),
                request.maxMembers(), request.rotationType(), request.nonCompliantBehavior(),
                request.reorganisationAllowed());
        return ResponseEntity.status(HttpStatus.CREATED).body(TontineResponse.from(tontine));
    }

    /**
     * Liste les tontines dont l'appelant authentifié est le créateur
     * (décision D1).
     */
    @GetMapping
    public ResponseEntity<List<TontineResponse>> listTontines(Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        List<TontineResponse> tontines = tontineApplicationService.listTontines(caller.userId()).stream()
                .map(TontineResponse::from)
                .toList();
        return ResponseEntity.ok(tontines);
    }

    @GetMapping("/{tontineId}")
    public ResponseEntity<TontineResponse> getTontine(@PathVariable Long tontineId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        Tontine tontine = tontineApplicationService.getTontine(tontineId, caller.userId());
        return ResponseEntity.ok(TontineResponse.from(tontine));
    }

    /**
     * Consulte le solde du compte TONTINE de cette tontine (décision R7).
     * Réservé au créateur. Solde dérivé du Ledger côté financial-service —
     * jamais mis en cache ici, jamais fourni par le client.
     */
    @GetMapping("/{tontineId}/balance")
    public ResponseEntity<TontineBalanceResponse> getBalance(
            @PathVariable Long tontineId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        AccountBalanceResponse balance = balanceApplicationService.getTontineBalance(
                tontineId, caller.userId(), authorizationHeader);
        return ResponseEntity.ok(new TontineBalanceResponse(tontineId, balance.currency(), balance.balance()));
    }

    /**
     * Consulte le relevé du compte TONTINE de cette tontine (décision R10,
     * symétrique à {@link #getBalance}). Réservé au créateur. Détail des
     * écritures derrière le solde déjà exposé.
     */
    @GetMapping("/{tontineId}/statement")
    public ResponseEntity<List<StatementLineResponse>> getStatement(
            @PathVariable Long tontineId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        List<StatementLineResponse> statement = balanceApplicationService
                .getTontineStatement(tontineId, caller.userId(), authorizationHeader)
                .stream()
                .map(StatementLineResponse::from)
                .toList();
        return ResponseEntity.ok(statement);
    }

    @PostMapping("/{tontineId}/members")
    public ResponseEntity<MemberResponse> addMember(
            @PathVariable Long tontineId, @Valid @RequestBody AddMemberRequest request, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineMember member = tontineApplicationService.addMember(
                tontineId, request.userId(), request.sequentialOrder(),
                request.displayName(), request.invitedPhone(), caller.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.from(member));
    }

    @GetMapping("/{tontineId}/members")
    public ResponseEntity<List<MemberResponse>> listMembers(@PathVariable Long tontineId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        List<MemberResponse> members = tontineApplicationService.listMembers(tontineId, caller.userId()).stream()
                .map(MemberResponse::from)
                .toList();
        return ResponseEntity.ok(members);
    }

    /**
     * Consulte le solde du compte MEMBER de ce membre au sein de cette
     * tontine (décision R8, symétrique à {@link #getBalance}). Réservé au
     * créateur. {@code memberId} revalidé comme appartenant à {@code
     * tontineId} avant tout appel à financial-service.
     */
    @GetMapping("/{tontineId}/members/{memberId}/balance")
    public ResponseEntity<MemberBalanceResponse> getMemberBalance(
            @PathVariable Long tontineId, @PathVariable Long memberId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        AccountBalanceResponse balance = balanceApplicationService.getMemberBalance(
                tontineId, memberId, caller.userId(), authorizationHeader);
        return ResponseEntity.ok(new MemberBalanceResponse(tontineId, memberId, balance.currency(), balance.balance()));
    }

    /**
     * Consulte le relevé du compte MEMBER de ce membre au sein de cette
     * tontine (décision R10, symétrique à {@link #getMemberBalance}).
     * Réservé au créateur. {@code memberId} revalidé comme appartenant à
     * {@code tontineId} avant tout appel à financial-service.
     */
    @GetMapping("/{tontineId}/members/{memberId}/statement")
    public ResponseEntity<List<StatementLineResponse>> getMemberStatement(
            @PathVariable Long tontineId, @PathVariable Long memberId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        List<StatementLineResponse> statement = balanceApplicationService
                .getMemberStatement(tontineId, memberId, caller.userId(), authorizationHeader)
                .stream()
                .map(StatementLineResponse::from)
                .toList();
        return ResponseEntity.ok(statement);
    }

    @GetMapping("/{tontineId}/config")
    public ResponseEntity<TontineConfigResponse> getConfig(@PathVariable Long tontineId, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineConfig config = tontineApplicationService.getConfig(tontineId, caller.userId());
        return ResponseEntity.ok(TontineConfigResponse.from(config));
    }

    @PutMapping("/{tontineId}/config")
    public ResponseEntity<TontineConfigResponse> updateConfig(
            @PathVariable Long tontineId, @Valid @RequestBody UpdateTontineConfigRequest request,
            Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        TontineConfig config = tontineApplicationService.updateConfig(
                tontineId, caller.userId(), request.contributionAmount(), request.contributionFrequency(),
                request.maxMembers(), request.rotationType(), request.nonCompliantBehavior(),
                request.reorganisationAllowed());
        return ResponseEntity.ok(TontineConfigResponse.from(config));
    }
}
