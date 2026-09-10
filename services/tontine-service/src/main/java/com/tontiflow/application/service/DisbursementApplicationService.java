package com.tontiflow.application.service;

import com.tontiflow.domain.enums.MemberStatus;
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
 * Cas d'usage « enregistrement administratif d'un versement au bénéficiaire »
 * (décision R6, symétrique à {@link ContributionApplicationService} —
 * décision R3, même modèle créateur-administré).
 *
 * <p>Contrairement à Contribution, aucun identifiant de membre n'est fourni
 * par le client : le bénéficiaire ({@link TontineRound#getBeneficiaryId()})
 * est déjà fixé sur le round (assigné par {@code assignNextRoundBeneficiary},
 * exclusivement parmi les membres de la tontine — donnée serveur, jamais une
 * entrée client à revalider par appartenance). La seule contrainte de donnée
 * est qu'un bénéficiaire doit avoir été assigné.</p>
 *
 * <p>Même frontière de propriété que Contribution (§5/§21, décision R3) :
 * aucune table financière n'est jamais touchée directement ici, aucune
 * modification de l'état métier tontine (le versement n'altère ni {@code
 * RoundStatus} ni aucun autre champ — même principe que Contribution).</p>
 */
@Service
public class DisbursementApplicationService {

    private final TontineRepository tontineRepository;
    private final TontineRoundRepository roundRepository;
    private final TontineMemberRepository memberRepository;
    private final FinancialServiceClient financialServiceClient;

    public DisbursementApplicationService(TontineRepository tontineRepository,
                                           TontineRoundRepository roundRepository,
                                           TontineMemberRepository memberRepository,
                                           FinancialServiceClient financialServiceClient) {
        this.tontineRepository = tontineRepository;
        this.roundRepository = roundRepository;
        this.memberRepository = memberRepository;
        this.financialServiceClient = financialServiceClient;
    }

    /**
     * Enregistre un versement au bénéficiaire de {@code roundId}, au nom du
     * créateur authentifié de {@code tontineId}.
     *
     * @throws IllegalArgumentException si la tontine/le round n'existe pas,
     *                                   si le round n'appartient pas à {@code tontineId},
     *                                   ou si aucun bénéficiaire n'est assigné
     * @throws org.springframework.security.access.AccessDeniedException si {@code callerUserId}
     *                                   n'est pas le créateur de {@code tontineId}
     */
    public TontineRound recordDisbursement(Long tontineId, Long roundId, UUID callerUserId,
                                            String authorizationHeader) {
        Tontine tontine = tontineRepository.findById(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Tontine non trouvée"));
        TontineApplicationService.requireCreator(tontine, callerUserId);

        TontineRound round = roundRepository.findById(roundId)
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));
        if (!round.getTontineId().equals(tontineId)) {
            throw new IllegalArgumentException("Ce round n'appartient pas à cette tontine");
        }

        Long beneficiaryId = round.getBeneficiaryId();
        if (beneficiaryId == null) {
            throw new IllegalArgumentException("Ce round n'a pas de bénéficiaire assigné");
        }

        // Décision R18 D5 : un membre PENDING (non lié à un compte TontiFlow)
        // ne peut pas recevoir de décaissement. Contrôle effectué côté
        // tontine-service AVANT tout appel à financial-service — aucune
        // écriture comptable n'est déclenchée pour un bénéficiaire PENDING.
        // beneficiaryId est un TontineMember.id (identité de participation),
        // jamais un accountId.
        TontineMember beneficiary = memberRepository.findById(beneficiaryId)
                .orElseThrow(() -> new IllegalArgumentException("Bénéficiaire du round introuvable"));
        if (beneficiary.getStatus() != MemberStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Le bénéficiaire n'est pas un membre actif (non lié à un compte) — décaissement impossible");
        }

        financialServiceClient.recordDisbursement(
                tontineId, roundId, beneficiaryId, round.getAmount(), authorizationHeader);

        return round;
    }
}
