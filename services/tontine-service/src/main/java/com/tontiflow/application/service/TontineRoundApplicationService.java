package com.tontiflow.application.service;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.*;
import com.tontiflow.domain.service.EligibilityEngine;
import com.tontiflow.domain.strategy.RotationStrategy;
import com.tontiflow.domain.strategy.RotationStrategyRegistry;
import com.tontiflow.infrastructure.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.tontiflow.domain.model.RoundRotationHistory;
import com.tontiflow.infrastructure.repository.RoundRotationHistoryRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TontineRoundApplicationService {

    private final TontineRoundRepository roundRepository;
    private final TontineMemberRepository memberRepository;
    private final TontineConfigRepository configRepository;
    private final RoundRotationHistoryRepository historyRepository;
    private final RotationStrategyRegistry strategyRegistry;
    private final EligibilityEngine eligibilityEngine;
    private final TontineRepository tontineRepository;

    public TontineRoundApplicationService(
            TontineRoundRepository roundRepository,
            TontineMemberRepository memberRepository,
            TontineConfigRepository configRepository,
            RoundRotationHistoryRepository historyRepository,
            RotationStrategyRegistry strategyRegistry,
            EligibilityEngine eligibilityEngine,
            TontineRepository tontineRepository) {
        this.roundRepository = roundRepository;
        this.memberRepository = memberRepository;
        this.configRepository = configRepository;
        this.historyRepository = historyRepository;
        this.strategyRegistry = strategyRegistry;
        this.eligibilityEngine = eligibilityEngine;
        this.tontineRepository = tontineRepository;
    }

    /**
     * Charge la tontine propriétaire du round et vérifie que l'appelant en
     * est le créateur — contrôle d'accès au niveau ressource, réutilisant
     * exactement le mécanisme déjà validé sur {@link TontineApplicationService}.
     *
     * @throws IllegalArgumentException si la tontine n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException
     *         si l'appelant n'est pas le créateur de la tontine
     */
    private Tontine requireCreatorOfRoundsTontine(Long tontineId, UUID callerUserId) {
        Tontine tontine = tontineRepository.findById(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Tontine non trouvée"));
        TontineApplicationService.requireCreator(tontine, callerUserId);
        return tontine;
    }

    @Transactional
    public TontineRound assignNextRoundBeneficiary(Long tontineId, Long roundId, UUID callerUserId) {
        TontineRound round = roundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));

        // Correctif R1 : le round doit réellement appartenir à la tontine indiquée
        // dans le chemin. Sans ce contrôle, le tontineId du chemin (jamais revalidé
        // auparavant) servait ensuite à charger la configuration et les membres —
        // un créateur pouvait ainsi faire attribuer un bénéficiaire d'une autre
        // tontine à un round qui ne lui appartient pas (corruption cross-tenant).
        // Traité comme "round non trouvé" (404) : du point de vue de tontineId,
        // aucun round de cet id ne lui appartient réellement.
        if (!round.getTontineId().equals(tontineId)) {
            throw new IllegalArgumentException("Round non trouvé");
        }

        requireCreatorOfRoundsTontine(round.getTontineId(), callerUserId);

        if (round.getStatus() == RoundStatus.ASSIGNED || round.getStatus() == RoundStatus.COMPLETED) {
            return round; // Idempotence : retourne le tour sans ré-attribution
        }

        // À partir d'ici, round.getTontineId() == tontineId (vérifié ci-dessus) —
        // utilisé directement pour ne dépendre que de la source de vérité réelle.
        return retryEligibility(round);
    }

    /**
     * Retente l'attribution d'un bénéficiaire pour un round donné (config,
     * membres, moteur d'éligibilité, stratégie de rotation) — logique
     * extraite d'{@link #assignNextRoundBeneficiary} (comportement
     * fonctionnel strictement inchangé), réutilisée à l'identique par deux
     * appelants :
     * <ul>
     *     <li>{@link #assignNextRoundBeneficiary} (déclenché par l'utilisateur
     *     créateur, après contrôle d'accès et vérification d'idempotence) ;</li>
     *     <li>{@code SuspendedRoundRetryScheduler} (décision S1b, déclenché par
     *     le système, après verrouillage pessimiste et revalidation du statut
     *     {@code SUSPENDED} — aucun {@code callerUserId} : le round est déjà
     *     identifié et légitimement accessible par le job planifié).</li>
     * </ul>
     * Bascule le round vers {@code SUSPENDED} si aucun membre n'est éligible,
     * ou vers {@code ASSIGNED} avec le bénéficiaire sélectionné sinon — même
     * règles qu'auparavant, aucune nouvelle règle métier introduite.
     */
    @Transactional
    TontineRound retryEligibility(TontineRound round) {
        TontineConfig config = configRepository.findByTontineId(round.getTontineId())
                .orElseThrow(() -> new IllegalStateException("Configuration introuvable"));

        List<TontineMember> members = memberRepository.findByTontineId(round.getTontineId());
        List<TontineRound> currentRounds = roundRepository.findByTontineId(round.getTontineId());

        List<TontineMember> eligibleMembers = members.stream()
                .filter(m -> eligibilityEngine.isEligible(m, config, currentRounds))
                .collect(Collectors.toList());

        if (eligibleMembers.isEmpty()) {
            round.setStatus(RoundStatus.SUSPENDED);
            return roundRepository.save(round);
        }

        RotationStrategy strategy = strategyRegistry.getStrategy(config.getRotationType());
        TontineMember selected = strategy.selectNextBeneficiary(eligibleMembers, currentRounds)
                .orElseThrow(() -> new IllegalStateException("Aucun bénéficiaire sélectionnable selon la stratégie"));

        round.setBeneficiaryId(selected.getId());
        round.setStatus(RoundStatus.ASSIGNED);

        return roundRepository.save(round);
    }

    @Transactional
    public TontineRound replaceBeneficiary(Long roundId, Long newBeneficiaryId, String reason, String actor,
                                            UUID callerUserId) {
        TontineRound round = roundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));
        requireCreatorOfRoundsTontine(round.getTontineId(), callerUserId);

        // Règle A (Phase F3) : un round COMPLETED est terminal — même invariant
        // que celui déjà appliqué par assignNextRoundBeneficiary (idempotence
        // sur ASSIGNED/COMPLETED). PLANNED et SUSPENDED restent volontairement
        // acceptés : aucune décision métier ne les exclut à ce stade.
        if (round.getStatus() == RoundStatus.COMPLETED) {
            throw new IllegalStateException("Impossible de remplacer le bénéficiaire d'un round déjà COMPLETED");
        }

        // Règle B (Phase F3) : le nouveau bénéficiaire doit appartenir à la
        // tontine du round — round.getTontineId() reste l'unique source de
        // vérité (même principe que le correctif R1), jamais une valeur
        // externe. Réutilise TontineMemberRepository.findByTontineId, déjà
        // utilisé par retryEligibility pour le même périmètre.
        boolean belongsToTontine = memberRepository.findByTontineId(round.getTontineId()).stream()
                .anyMatch(m -> m.getId().equals(newBeneficiaryId));
        if (!belongsToTontine) {
            throw new IllegalArgumentException("Le bénéficiaire indiqué n'appartient pas à cette tontine");
        }

        Long previousBeneficiaryId = round.getBeneficiaryId();

        RoundRotationHistory history = new RoundRotationHistory();
        history.setRoundId(roundId);
        history.setPreviousBeneficiaryId(previousBeneficiaryId);
        history.setNewBeneficiaryId(newBeneficiaryId);
        history.setReason(reason);
        history.setUpdatedBy(actor);
        history.setTimestamp(LocalDateTime.now());
        history.setModificationType("REPLACEMENT");

        historyRepository.save(history);

        round.setBeneficiaryId(newBeneficiaryId);
        round.setStatus(RoundStatus.ASSIGNED);

        return roundRepository.save(round);
    }

    /**
     * Crée un nouveau round {@code PLANNED} pour la tontine donnée — utilisé à
     * la fois pour le premier round (décision B1, appelé depuis
     * {@link TontineApplicationService#createTontine}) et pour les rounds
     * suivants (décision C2/M1, appelé depuis {@link RoundCompletionScheduler}
     * lorsqu'un round précédent passe à {@code COMPLETED}).
     *
     * <p>Règles appliquées (décisions verrouillées) : {@code roundNumber} fourni
     * par l'appelant (D1 pour le premier round, E1 = dernier + 1 pour les
     * suivants) ; {@code startDate} = horodatage de création (F1/Q1, non dérivé
     * du round précédent) ; {@code endDate} = {@code startDate} + durée selon
     * {@code contributionFrequency} (G1/N1) ; {@code amount} = {@code
     * contributionAmount} de la configuration (H1) ; statut initial {@code
     * PLANNED} (I) ; {@code beneficiaryId} initial {@code null} (J).</p>
     *
     * @throws IllegalStateException si un round {@code PLANNED} existe déjà
     *                                pour cette tontine (K1 : un seul PLANNED
     *                                maximum — protection interne, traduction
     *                                de L1 en l'absence d'endpoint HTTP de
     *                                création manuelle, cf. plan Phase C §7)
     */
    @Transactional
    TontineRound createRoundForTontine(Long tontineId, TontineConfig config, int roundNumber) {
        boolean plannedRoundAlreadyExists = roundRepository.findByTontineId(tontineId).stream()
                .anyMatch(r -> r.getStatus() == RoundStatus.PLANNED);
        if (plannedRoundAlreadyExists) {
            throw new IllegalStateException(
                    "Un round PLANNED existe déjà pour la tontine " + tontineId);
        }

        LocalDateTime startDate = LocalDateTime.now();
        LocalDateTime endDate = computeEndDate(startDate, config.getContributionFrequency());

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(roundNumber);
        round.setAmount(config.getContributionAmount());
        round.setStartDate(startDate);
        round.setEndDate(endDate);
        round.setStatus(RoundStatus.PLANNED);

        return roundRepository.save(round);
    }

    private static LocalDateTime computeEndDate(LocalDateTime startDate, ContributionFrequency frequency) {
        return switch (frequency) {
            case DAILY -> startDate.plusDays(1);
            case WEEKLY -> startDate.plusWeeks(1);
            case MONTHLY -> startDate.plusMonths(1);
        };
    }

    /**
     * Liste les rounds d'une tontine (décision A3). Réservé au créateur de la
     * tontine (décision B1).
     *
     * @throws IllegalArgumentException si la tontine n'existe pas
     * @throws org.springframework.security.access.AccessDeniedException
     *         si l'appelant n'est pas le créateur de la tontine
     */
    public List<TontineRound> listRounds(Long tontineId, UUID callerUserId) {
        requireCreatorOfRoundsTontine(tontineId, callerUserId);
        return roundRepository.findByTontineId(tontineId);
    }

    /**
     * Lit un round précis d'une tontine (décision A3). Réservé au créateur de
     * la tontine (décision B1). Le round doit réellement appartenir à
     * {@code tontineId} — même principe de cohérence que le correctif R1.
     *
     * @throws IllegalArgumentException si la tontine n'existe pas, ou si le
     *                                  round n'existe pas / n'appartient pas
     *                                  à cette tontine
     * @throws org.springframework.security.access.AccessDeniedException
     *         si l'appelant n'est pas le créateur de la tontine
     */
    public TontineRound getRound(Long tontineId, Long roundId, UUID callerUserId) {
        requireCreatorOfRoundsTontine(tontineId, callerUserId);
        return roundRepository.findById(roundId)
                .filter(r -> r.getTontineId().equals(tontineId))
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));
    }

    /**
     * Historique des remplacements de bénéficiaire pour un round (décision
     * R9) — expose {@link RoundRotationHistory}, déjà intégralement écrite
     * par {@link #replaceBeneficiary} mais jamais relue jusqu'ici. Réutilise
     * {@link #getRound} pour l'autorisation créateur et la revalidation
     * d'appartenance round↔tontine, aucune nouvelle règle introduite.
     *
     * @throws IllegalArgumentException si la tontine n'existe pas, ou si le
     *                                  round n'existe pas / n'appartient pas
     *                                  à cette tontine
     * @throws org.springframework.security.access.AccessDeniedException
     *         si l'appelant n'est pas le créateur de la tontine
     */
    public List<RoundRotationHistory> listRotationHistory(Long tontineId, Long roundId, UUID callerUserId) {
        TontineRound round = getRound(tontineId, roundId, callerUserId);
        return historyRepository.findByRoundId(round.getId());
    }

    /**
     * Round « courant » d'une tontine (décision C2) : interprété comme le
     * round non terminal le plus avancé (statut {@code PLANNED} ou
     * {@code ASSIGNED}) — cohérent avec la décision K1 (un seul
     * {@code PLANNED} maximum) et le cycle de vie déjà codé, où un seul round
     * est actif à la fois. Un round {@code SUSPENDED} n'est volontairement
     * pas considéré « courant » (aucun mécanisme ne le fait progresser,
     * cf. audit Phase A) ; cette interprétation est une déduction technique
     * du code existant, pas une nouvelle règle métier inventée — à confirmer
     * séparément si elle doit inclure les rounds {@code SUSPENDED}.
     *
     * @throws IllegalArgumentException si la tontine n'existe pas, ou si
     *                                  aucun round {@code PLANNED}/{@code
     *                                  ASSIGNED} n'existe pour cette tontine
     * @throws org.springframework.security.access.AccessDeniedException
     *         si l'appelant n'est pas le créateur de la tontine
     */
    public TontineRound getCurrentRound(Long tontineId, UUID callerUserId) {
        requireCreatorOfRoundsTontine(tontineId, callerUserId);
        return roundRepository.findByTontineId(tontineId).stream()
                .filter(r -> r.getStatus() == RoundStatus.PLANNED || r.getStatus() == RoundStatus.ASSIGNED)
                .max(Comparator.comparingInt(TontineRound::getRoundNumber))
                .orElseThrow(() -> new IllegalArgumentException("Aucun round actif pour cette tontine"));
    }
}