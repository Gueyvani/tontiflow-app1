package com.tontiflow.application.service;

import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.client.FinancialServiceClient;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Cas d'usage « enregistrement administratif d'une contribution » (décision
 * R3, modèle créateur-administré — cf. rapport d'inspection Étape 1 :
 * {@code TontineMember.userId} n'est actuellement lié à aucun {@code UUID}
 * JWT authentifiable, un flux self-service est donc impossible sans
 * inventer ce lien).
 *
 * <p>Frontière de propriété des données strictement respectée (décision
 * R3, §5/§21) : ce service ne touche <b>jamais</b> directement une table
 * financière — il valide entièrement l'autorisation et l'appartenance
 * côté tontine, détermine le montant depuis {@link TontineRound#getAmount()},
 * puis délègue l'écriture comptable à {@code financial-service} via {@link
 * FinancialServiceClient}. Aucune modification de l'état métier tontine
 * n'est effectuée ici (§21 : "mettre à jour l'état métier tontine
 * uniquement selon les règles déjà existantes" — R3 n'introduit aucune
 * nouvelle règle d'état, {@code paidMandatoryContribution} reste hors
 * périmètre, cf. §18).</p>
 */
@Service
public class ContributionApplicationService {

    private final TontineRepository tontineRepository;
    private final TontineRoundRepository roundRepository;
    private final TontineMemberRepository memberRepository;
    private final FinancialServiceClient financialServiceClient;

    public ContributionApplicationService(TontineRepository tontineRepository,
                                           TontineRoundRepository roundRepository,
                                           TontineMemberRepository memberRepository,
                                           FinancialServiceClient financialServiceClient) {
        this.tontineRepository = tontineRepository;
        this.roundRepository = roundRepository;
        this.memberRepository = memberRepository;
        this.financialServiceClient = financialServiceClient;
    }

    /**
     * Enregistre une contribution pour {@code memberId} sur {@code
     * roundId}, au nom du créateur authentifié de {@code tontineId}.
     *
     * <p>Ordre strict (décision R3, §22) : authentification (déjà garantie
     * par {@code SecurityConfig} avant d'atteindre ce point) → autorisation
     * créateur → validation round → validation membre → montant (round) →
     * appel financial-service. Chaque identifiant reçu du client
     * ({@code tontineId}/{@code roundId} du chemin, {@code memberId} du
     * corps) est revalidé en base — jamais supposé cohérent (anti-BOLA/IDOR,
     * §7/§8/§44).</p>
     *
     * @throws IllegalArgumentException si la tontine/le round/le membre n'existe pas,
     *                                   ou si le round/le membre n'appartient pas à {@code tontineId}
     * @throws org.springframework.security.access.AccessDeniedException si {@code callerUserId}
     *                                   n'est pas le créateur de {@code tontineId}
     */
    public TontineRound recordContribution(Long tontineId, Long roundId, Long memberId, UUID callerUserId,
                                            String authorizationHeader) {
        Tontine tontine = tontineRepository.findById(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Tontine non trouvée"));
        TontineApplicationService.requireCreator(tontine, callerUserId);

        TontineRound round = roundRepository.findById(roundId)
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));
        if (!round.getTontineId().equals(tontineId)) {
            throw new IllegalArgumentException("Ce round n'appartient pas à cette tontine");
        }

        TontineMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Membre non trouvé"));
        if (!member.getTontineId().equals(tontineId)) {
            throw new IllegalArgumentException("Ce membre n'appartient pas à cette tontine");
        }

        financialServiceClient.recordContribution(
                tontineId, roundId, memberId, round.getAmount(), authorizationHeader);

        return round;
    }
}
