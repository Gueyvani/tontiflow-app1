package com.tontiflow.application.service;

import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.client.AccountBalanceResponse;
import com.tontiflow.infrastructure.client.FinancialServiceClient;
import com.tontiflow.infrastructure.client.LedgerLineResponse;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Cas d'usage « consultation administrative du solde » (décision R7,
 * candidat recommandé du rapport d'inspection Étape 1 — {@code
 * LedgerService.computeBalance} existait déjà côté financial-service, testé,
 * mais n'était exposé par aucun contrôleur). Même modèle créateur-administré
 * que {@link ContributionApplicationService}/{@link
 * DisbursementApplicationService} (décisions R3/R6) : lecture seule, aucune
 * écriture financière.
 *
 * <p>Ne touche jamais directement une table financière : valide entièrement
 * l'autorisation côté tontine, puis délègue à {@code financial-service} via
 * {@link FinancialServiceClient}. {@code ownerReference}/{@code accountType}
 * ne sont jamais fournis par le client — dérivés exclusivement de {@code
 * tontineId} (compte TONTINE) côté serveur.</p>
 */
@Service
public class BalanceApplicationService {

    private final TontineRepository tontineRepository;
    private final TontineMemberRepository memberRepository;
    private final FinancialServiceClient financialServiceClient;

    public BalanceApplicationService(TontineRepository tontineRepository,
                                      TontineMemberRepository memberRepository,
                                      FinancialServiceClient financialServiceClient) {
        this.tontineRepository = tontineRepository;
        this.memberRepository = memberRepository;
        this.financialServiceClient = financialServiceClient;
    }

    /**
     * Consulte le solde du compte TONTINE de {@code tontineId}, au nom du
     * créateur authentifié.
     *
     * @throws IllegalArgumentException si la tontine n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si {@code callerUserId}
     *                                   n'est pas le créateur de {@code tontineId}
     */
    public AccountBalanceResponse getTontineBalance(Long tontineId, UUID callerUserId, String authorizationHeader) {
        requireCreatorOfTontine(tontineId, callerUserId);
        return financialServiceClient.getBalance(tontineId, authorizationHeader);
    }

    /**
     * Consulte le relevé du compte TONTINE de {@code tontineId} (décision
     * R10, symétrique à {@link #getTontineBalance} — décision R7) : détail
     * des écritures derrière le solde déjà exposé.
     *
     * @throws IllegalArgumentException si la tontine n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException si {@code callerUserId}
     *                                   n'est pas le créateur de {@code tontineId}
     */
    public List<LedgerLineResponse> getTontineStatement(Long tontineId, UUID callerUserId, String authorizationHeader) {
        requireCreatorOfTontine(tontineId, callerUserId);
        return financialServiceClient.getStatement(tontineId, authorizationHeader);
    }

    /**
     * Consulte le solde du compte MEMBER de {@code memberId} au sein de
     * {@code tontineId}, au nom du créateur authentifié (décision R8,
     * symétrique à {@link #getTontineBalance}). Revalidation d'appartenance
     * identique à {@link ContributionApplicationService#recordContribution}
     * (§7/§8, décision R3) : {@code memberId} n'est jamais supposé
     * appartenir à {@code tontineId} uniquement parce qu'il figure dans
     * l'URL.
     *
     * @throws IllegalArgumentException si la tontine ou le membre n'existe pas,
     *                                   ou si le membre n'appartient pas à {@code tontineId}
     * @throws org.springframework.security.access.AccessDeniedException si {@code callerUserId}
     *                                   n'est pas le créateur de {@code tontineId}
     */
    public AccountBalanceResponse getMemberBalance(Long tontineId, Long memberId, UUID callerUserId,
                                                    String authorizationHeader) {
        requireMemberOfTontine(tontineId, memberId, callerUserId);
        return financialServiceClient.getMemberBalance(memberId, authorizationHeader);
    }

    /**
     * Consulte le relevé du compte MEMBER de {@code memberId} au sein de
     * {@code tontineId} (décision R10, symétrique à {@link #getMemberBalance}
     * — décision R8) : détail des écritures derrière le solde déjà exposé.
     *
     * @throws IllegalArgumentException si la tontine ou le membre n'existe pas,
     *                                   ou si le membre n'appartient pas à {@code tontineId}
     * @throws org.springframework.security.access.AccessDeniedException si {@code callerUserId}
     *                                   n'est pas le créateur de {@code tontineId}
     */
    public List<LedgerLineResponse> getMemberStatement(Long tontineId, Long memberId, UUID callerUserId,
                                                         String authorizationHeader) {
        requireMemberOfTontine(tontineId, memberId, callerUserId);
        return financialServiceClient.getMemberStatement(memberId, authorizationHeader);
    }

    private void requireCreatorOfTontine(Long tontineId, UUID callerUserId) {
        Tontine tontine = tontineRepository.findById(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Tontine non trouvée"));
        TontineApplicationService.requireCreator(tontine, callerUserId);
    }

    private void requireMemberOfTontine(Long tontineId, Long memberId, UUID callerUserId) {
        requireCreatorOfTontine(tontineId, callerUserId);
        TontineMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Membre non trouvé"));
        if (!member.getTontineId().equals(tontineId)) {
            throw new IllegalArgumentException("Ce membre n'appartient pas à cette tontine");
        }
    }
}
