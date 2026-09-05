package com.tontiflow.application.service;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Complétion automatique des rounds selon leur échéance (décision O2) et
 * création du round suivant (décisions C2/M1).
 *
 * <p><b>Périmètre explicitement limité aux rounds {@code ASSIGNED}</b> :
 * seul un round ayant un bénéficiaire attribué correspond, dans le code
 * existant, à un cycle de cotisation réellement en cours. Un round
 * {@code SUSPENDED} (aucun membre éligible trouvé, cf. {@code
 * assignNextRoundBeneficiary}) n'a pas de bénéficiaire et aucun chemin de
 * code existant ne le fait sortir de cet état — il est donc exclu de cette
 * complétion automatique. Cette restriction est une déduction du
 * comportement déjà codé, disclosed ici, et non une règle métier
 * supplémentaire inventée ; elle devra être revalidée explicitement si la
 * gestion des rounds {@code SUSPENDED} évolue.</p>
 *
 * <p>Fréquence de vérification : détail d'implémentation technique (et non
 * une décision métier), fixée par défaut à 60 secondes, configurable via la
 * propriété {@code tontine.round.completion-check-interval-ms}.</p>
 */
@Component
public class RoundCompletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoundCompletionScheduler.class);

    private final TontineRoundRepository roundRepository;
    private final TontineConfigRepository configRepository;
    private final TontineRoundApplicationService roundApplicationService;

    /**
     * Référence différée vers le proxy Spring de ce bean lui-même —
     * nécessaire pour que l'appel à {@link #completeRoundAndCreateNext}
     * depuis {@link #completeExpiredRounds} passe bien par le proxy AOP et
     * active réellement {@code @Transactional} (une auto-invocation directe
     * via {@code this} contourne le proxy et désactive silencieusement la
     * transaction — bug réel découvert par exécution avec Spring/H2 réels,
     * jamais détecté auparavant car les tests existants mockaient les
     * repositories). {@code @Lazy} évite un cycle de construction.
     */
    private RoundCompletionScheduler self;

    public RoundCompletionScheduler(TontineRoundRepository roundRepository,
                                     TontineConfigRepository configRepository,
                                     TontineRoundApplicationService roundApplicationService) {
        this.roundRepository = roundRepository;
        this.configRepository = configRepository;
        this.roundApplicationService = roundApplicationService;
    }

    @Autowired
    public void setSelf(@Lazy RoundCompletionScheduler self) {
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${tontine.round.completion-check-interval-ms:60000}")
    public void completeExpiredRounds() {
        List<TontineRound> expired =
                roundRepository.findByStatusAndEndDateBefore(RoundStatus.ASSIGNED, LocalDateTime.now());

        for (TontineRound round : expired) {
            try {
                self.completeRoundAndCreateNext(round.getId());
            } catch (Exception e) {
                log.error("Échec de la complétion automatique du round {} : {}", round.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Traite un round expiré individuellement, dans sa propre transaction —
     * l'échec d'un round n'affecte pas le traitement des autres. Verrouille
     * le round (réutilisation du mécanisme déjà validé pour
     * assign-beneficiary/replace-beneficiary) et revérifie son état avant
     * modification, pour se protéger d'une exécution concurrente du job.
     *
     * <p><b>Décision P1</b> : {@code TontineConfig} est recherchée <i>avant</i>
     * toute écriture de statut. Si absente, le round bascule vers {@code
     * BLOCKED} (jamais {@code COMPLETED}) — un état métier volontaire, commité
     * normalement (pas d'exception ni de rollback), retraité périodiquement
     * par {@link BlockedRoundRetryScheduler}. Ceci élimine l'ancien
     * comportement (rollback transactionnel + nouvelle tentative silencieuse
     * toutes les 60 secondes) et garantit qu'un round ne peut jamais devenir
     * {@code COMPLETED} sans que la création du round suivant ait au moins
     * été tentée.</p>
     */
    @Transactional
    void completeRoundAndCreateNext(Long roundId) {
        TontineRound round = roundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));

        if (round.getStatus() != RoundStatus.ASSIGNED || round.getEndDate().isAfter(LocalDateTime.now())) {
            return; // état déjà modifié entre-temps (concurrence) : rien à faire
        }

        var config = configRepository.findByTontineId(round.getTontineId());
        if (config.isEmpty()) {
            round.setStatus(RoundStatus.BLOCKED);
            roundRepository.save(round);
            log.error("Round {} (tontine {}) bloqué : configuration introuvable pour la tontine {} — "
                            + "en attente de correction, retraité automatiquement par {}.",
                    roundId, round.getTontineId(), round.getTontineId(),
                    BlockedRoundRetryScheduler.class.getSimpleName());
            return;
        }

        completeNow(round, config.get());
    }

    /**
     * Marque {@code round} {@code COMPLETED} et tente la création du round
     * suivant — extrait de {@link #completeRoundAndCreateNext} pour être
     * réutilisé tel quel par {@link BlockedRoundRetryScheduler} une fois la
     * {@code TontineConfig} disponible, sans dupliquer cette logique.
     * {@code round} doit déjà être verrouillé (chargé via {@code
     * findByIdForUpdate}) par l'appelant, dans une transaction déjà active.
     */
    void completeNow(TontineRound round, TontineConfig config) {
        round.setStatus(RoundStatus.COMPLETED);
        roundRepository.save(round);

        try {
            roundApplicationService.createRoundForTontine(
                    round.getTontineId(), config, round.getRoundNumber() + 1);
        } catch (IllegalStateException e) {
            log.warn("Round suivant non créé pour la tontine {} : {}", round.getTontineId(), e.getMessage());
        }
    }
}
